package com.yuyu.fishagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.common.trace.TraceProperties;
import com.yuyu.fishagent.common.trace.TurnTrace;
import com.yuyu.fishagent.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * 真实工程指标聚合服务。
 *
 * <p>聚合三类数据源：
 * 1. RAG golden set 的真实检索评测；
 * 2. 排障 golden set 的路由 / 工具 / 引用评测；
 * 3. 本地 turn trace 的真实时延与模型调用样本。</p>
 */
@Service
@RequiredArgsConstructor
public class EngineeringMetricsService {

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final RagEvalService ragEvalService;
    private final RagEvalProperties ragEvalProperties;
    private final EngineeringMetricsProperties engineeringMetricsProperties;
    private final TraceProperties traceProperties;
    private final RagProperties ragProperties;

    public EngineeringMetricsReport run(Integer k, Integer perLegK) {
        int topK = positiveOrDefault(k, ragEvalProperties.getK());
        int legK = positiveOrDefault(perLegK, ragEvalProperties.getPerLegK());

        HybridEvalReport ragReport = ragEvalService.run(topK, legK);
        TroubleshootingEvalReport troubleshootingReport =
                new TroubleshootingEvalRunner().run(loadTroubleshootingGoldenSet(), topK);
        TurnTraceSummary turnTraceSummary = summarizeTurnTraces();
        int ragTraceCount = countJsonFiles(ragProperties.getTracing().getStorageDir());

        HybridEvalReport.Variant primaryVariant = selectPrimaryVariant(ragReport.variants());
        EngineeringMetricsReport.RankingMetrics primaryMetrics =
                toRankingMetrics(ragReport.variants().get(primaryVariant));
        Map<HybridEvalReport.Variant, EngineeringMetricsReport.RankingMetrics> variants =
                new EnumMap<>(HybridEvalReport.Variant.class);
        for (Map.Entry<HybridEvalReport.Variant, HybridEvalReport.VariantReport> entry : ragReport.variants().entrySet()) {
            variants.put(entry.getKey(), toRankingMetrics(entry.getValue()));
        }

        List<String> notes = new ArrayList<>();
        if (turnTraceSummary.turnTraceCount == 0) {
            notes.add("turn trace 样本为空，p95 时延、恢复时间与模型调用成本指标暂时返回 0。");
        }
        if (engineeringMetricsProperties.getPromptTokenCostUsdPer1k() <= 0) {
            notes.add("fish.eval.engineering.prompt-token-cost-usd-per1k 未配置，平均 token 成本当前按 0 计算。");
        } else {
            notes.add("平均 token 成本当前按 prompt 估算，不含 completion token。");
        }

        return new EngineeringMetricsReport(
                System.currentTimeMillis(),
                new EngineeringMetricsReport.SampleStats(
                        troubleshootingReport.caseCount(),
                        ragReport.caseCount(),
                        turnTraceSummary.turnTraceCount,
                        ragTraceCount
                ),
                new EngineeringMetricsReport.RoutingMetrics(
                        troubleshootingReport.routeAccuracy(),
                        troubleshootingReport.routeF1()
                ),
                new EngineeringMetricsReport.ToolMetrics(
                        troubleshootingReport.toolSelectionAccuracy(),
                        troubleshootingReport.toolParameterAccuracy(),
                        troubleshootingReport.averageToolCalls()
                ),
                new EngineeringMetricsReport.RetrievalMetrics(
                        primaryVariant,
                        primaryMetrics,
                        variants
                ),
                new EngineeringMetricsReport.CitationMetrics(
                        troubleshootingReport.citationAccuracy(),
                        troubleshootingReport.citationCoverage()
                ),
                new EngineeringMetricsReport.LatencyMetrics(
                        turnTraceSummary.ttftP95Ms,
                        turnTraceSummary.fullResponseP95Ms,
                        turnTraceSummary.failureRecoveryP95Ms
                ),
                new EngineeringMetricsReport.EfficiencyMetrics(
                        turnTraceSummary.averageModelCallsPerTurn,
                        turnTraceSummary.averagePromptTokensPerTurn,
                        turnTraceSummary.averageEstimatedPromptCostUsd
                ),
                List.copyOf(notes)
        );
    }

    private TroubleshootingGoldenSet loadTroubleshootingGoldenSet() {
        String path = engineeringMetricsProperties.getTroubleshootingGoldenSetPath();
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("排障 golden set 路径未配置");
        }
        Resource resource = resourceLoader.getResource(path.trim());
        if (!resource.exists()) {
            throw new IllegalStateException("排障 golden set 不存在: " + path);
        }
        try (InputStream input = resource.getInputStream()) {
            TroubleshootingGoldenSet.Case[] cases = objectMapper.readValue(input, TroubleshootingGoldenSet.Case[].class);
            if (cases == null || cases.length == 0) {
                throw new IllegalStateException("排障 golden set 为空: " + path);
            }
            return new TroubleshootingGoldenSet(List.of(cases));
        } catch (IOException e) {
            throw new IllegalStateException("排障 golden set 读取失败: " + path, e);
        }
    }

    private TurnTraceSummary summarizeTurnTraces() {
        Path dir = Paths.get(traceProperties.getStorageDir());
        if (!Files.isDirectory(dir)) {
            return TurnTraceSummary.empty();
        }
        List<TurnTrace> traces = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> readTrace(path).ifPresent(traces::add));
        } catch (IOException ignored) {
            return TurnTraceSummary.empty();
        }
        if (traces.isEmpty()) {
            return TurnTraceSummary.empty();
        }

        List<Long> ttftValues = new ArrayList<>();
        List<Long> fullResponseValues = new ArrayList<>();
        List<Long> recoveryValues = new ArrayList<>();
        double modelCalls = 0.0;
        double promptTokens = 0.0;
        double promptCost = 0.0;
        double pricePerToken = engineeringMetricsProperties.getPromptTokenCostUsdPer1k() / 1000.0;

        Map<String, List<TurnTrace>> bySession = new LinkedHashMap<>();
        for (TurnTrace trace : traces) {
            OptionalLong ttft = timeToFirstToken(trace);
            ttft.ifPresent(value -> ttftValues.add(value));
            if (trace.getTotalLatencyMs() > 0) {
                fullResponseValues.add(trace.getTotalLatencyMs());
            }
            int callCount = trace.getPrompts() == null ? 0 : trace.getPrompts().size();
            modelCalls += callCount;
            int traceTokens = trace.getPrompts() == null ? 0 : trace.getPrompts().stream()
                    .filter(prompt -> prompt != null)
                    .mapToInt(prompt -> Math.max(0, prompt.getEstimatedTokens()))
                    .sum();
            promptTokens += traceTokens;
            promptCost += traceTokens * pricePerToken;
            String sessionKey = trace.getSessionId();
            if (sessionKey != null && !sessionKey.isBlank()) {
                bySession.computeIfAbsent(sessionKey, key -> new ArrayList<>()).add(trace);
            }
        }

        for (List<TurnTrace> sessionTraces : bySession.values()) {
            sessionTraces.sort(Comparator.comparingLong(TurnTrace::getStartTimeMs));
            for (int i = 0; i < sessionTraces.size(); i++) {
                TurnTrace current = sessionTraces.get(i);
                if (!"ERROR".equalsIgnoreCase(current.getStatus())) {
                    continue;
                }
                for (int j = i + 1; j < sessionTraces.size(); j++) {
                    TurnTrace next = sessionTraces.get(j);
                    if ("SUCCESS".equalsIgnoreCase(next.getStatus())) {
                        long recoveredAt = next.getStartTimeMs() + Math.max(0L, next.getTotalLatencyMs());
                        long delta = recoveredAt - current.getStartTimeMs();
                        if (delta > 0) {
                            recoveryValues.add(delta);
                        }
                        break;
                    }
                }
            }
        }

        double traceCount = traces.size();
        return new TurnTraceSummary(
                traces.size(),
                percentile95(ttftValues),
                percentile95(fullResponseValues),
                percentile95(recoveryValues),
                modelCalls / traceCount,
                promptTokens / traceCount,
                promptCost / traceCount
        );
    }

    private OptionalLong timeToFirstToken(TurnTrace trace) {
        if (trace == null || trace.getNodes() == null || trace.getNodes().isEmpty()) {
            return OptionalLong.empty();
        }
        int firstPromptOrder = trace.getPrompts() == null || trace.getPrompts().isEmpty()
                ? Integer.MIN_VALUE
                : trace.getPrompts().stream()
                .filter(prompt -> prompt != null)
                .mapToInt(TurnTrace.PromptCall::getOrder)
                .min()
                .orElse(Integer.MIN_VALUE);

        return trace.getNodes().stream()
                .filter(node -> node != null)
                .filter(node -> !"router".equalsIgnoreCase(node.getType()))
                .filter(node -> firstPromptOrder == Integer.MIN_VALUE || node.getOrder() > firstPromptOrder)
                .sorted(Comparator.comparingInt(TurnTrace.Node::getOrder))
                .mapToLong(node -> Math.max(0L, node.getLatencyMs()))
                .findFirst();
    }

    private java.util.Optional<TurnTrace> readTrace(Path path) {
        try {
            return java.util.Optional.of(objectMapper.readValue(path.toFile(), TurnTrace.class));
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }

    private int countJsonFiles(String storageDir) {
        if (storageDir == null || storageDir.isBlank()) {
            return 0;
        }
        Path dir = Paths.get(storageDir);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var stream = Files.list(dir)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .count();
        } catch (IOException ignored) {
            return 0;
        }
    }

    private EngineeringMetricsReport.RankingMetrics toRankingMetrics(HybridEvalReport.VariantReport report) {
        if (report == null) {
            return new EngineeringMetricsReport.RankingMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
        return new EngineeringMetricsReport.RankingMetrics(
                report.metrics().precisionAtK(),
                report.metrics().recallAtK(),
                report.metrics().mrr(),
                report.metrics().ndcgAtK(),
                report.averageLatencyMs(),
                report.averageEstimatedCostUsd()
        );
    }

    private HybridEvalReport.Variant selectPrimaryVariant(Map<HybridEvalReport.Variant, HybridEvalReport.VariantReport> variants) {
        if (variants.containsKey(HybridEvalReport.Variant.HYBRID_RERANK)) {
            return HybridEvalReport.Variant.HYBRID_RERANK;
        }
        if (variants.containsKey(HybridEvalReport.Variant.HYBRID)) {
            return HybridEvalReport.Variant.HYBRID;
        }
        if (variants.containsKey(HybridEvalReport.Variant.LEXICAL_ONLY)) {
            return HybridEvalReport.Variant.LEXICAL_ONLY;
        }
        return variants.keySet().stream().findFirst().orElse(HybridEvalReport.Variant.DENSE_ONLY);
    }

    private double percentile95(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        List<Long> sorted = values.stream()
                .filter(value -> value != null && value >= 0)
                .sorted()
                .toList();
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1);
        return sorted.get(Math.min(index, sorted.size() - 1));
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? Math.max(1, fallback) : value;
    }

    private record TurnTraceSummary(int turnTraceCount,
                                    double ttftP95Ms,
                                    double fullResponseP95Ms,
                                    double failureRecoveryP95Ms,
                                    double averageModelCallsPerTurn,
                                    double averagePromptTokensPerTurn,
                                    double averageEstimatedPromptCostUsd) {

        private static TurnTraceSummary empty() {
            return new TurnTraceSummary(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
    }
}
