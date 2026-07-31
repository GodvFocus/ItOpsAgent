package com.ai.itops.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.itops.common.trace.TraceProperties;
import com.ai.itops.common.trace.TurnTrace;
import com.ai.itops.rag.config.RagProperties;
import com.ai.itops.rag.pipeline.recall.RagRecall;
import com.ai.itops.rag.pipeline.rerank.RagReranker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EngineeringMetricsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void aggregatesOfflineAndTraceMetricsIntoSingleReport() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Path traceDir = Files.createDirectory(tempDir.resolve("traces"));
        Path ragTraceDir = Files.createDirectory(tempDir.resolve("rag-traces"));

        writeTrace(objectMapper, traceDir.resolve("turn-1.json"), successTrace("turn-1", "s-1", 1_000L, 1_000L, 120L, List.of(100)));
        writeTrace(objectMapper, traceDir.resolve("turn-2.json"), errorTrace("turn-2", "s-1", 3_000L, 700L, 250L, List.of(200)));
        writeTrace(objectMapper, traceDir.resolve("turn-3.json"), successTrace("turn-3", "s-1", 8_000L, 900L, 400L, List.of(300, 50)));
        Files.writeString(ragTraceDir.resolve("rag-1.json"), "{\"traceId\":\"rag-1\"}");

        RagEvalProperties ragEvalProperties = new RagEvalProperties();
        RagProperties ragProperties = new RagProperties();
        ragProperties.getTracing().setStorageDir(ragTraceDir.toString());
        RagEvalService ragEvalService = new RagEvalService(
                objectMapper,
                new DefaultResourceLoader(),
                ragEvalProperties,
                ragProperties,
                List.of(new EmptySearcher()),
                new PassThroughReranker());

        EngineeringMetricsProperties engineeringMetricsProperties = new EngineeringMetricsProperties();
        engineeringMetricsProperties.setTroubleshootingGoldenSetPath("classpath:eval/golden-troubleshooting.json");
        engineeringMetricsProperties.setPromptTokenCostUsdPer1k(0.5);

        TraceProperties traceProperties = new TraceProperties();
        traceProperties.setStorageDir(traceDir.toString());

        EngineeringMetricsService service = new EngineeringMetricsService(
                objectMapper,
                new DefaultResourceLoader(),
                ragEvalService,
                ragEvalProperties,
                engineeringMetricsProperties,
                traceProperties,
                ragProperties);

        EngineeringMetricsReport report = service.run(3, 3);

        assertThat(report.samples().troubleshootingCaseCount()).isEqualTo(4);
        assertThat(report.samples().ragCaseCount()).isEqualTo(20);
        assertThat(report.samples().turnTraceCount()).isEqualTo(3);
        assertThat(report.samples().ragTraceCount()).isEqualTo(1);

        assertThat(report.routing().accuracy()).isEqualTo(1.0);
        assertThat(report.routing().f1()).isEqualTo(1.0);
        assertThat(report.tools().selectionAccuracy()).isEqualTo(1.0);
        assertThat(report.tools().parameterAccuracy()).isEqualTo(1.0);

        assertThat(report.latency().ttftP95Ms()).isEqualTo(400.0);
        assertThat(report.latency().fullResponseP95Ms()).isEqualTo(1_000.0);
        assertThat(report.latency().failureRecoveryP95Ms()).isEqualTo(5_900.0);

        assertThat(report.efficiency().averageModelCallsPerTurn()).isCloseTo(4.0 / 3.0, within(0.0001));
        assertThat(report.efficiency().averagePromptTokensPerTurn()).isCloseTo(650.0 / 3.0, within(0.0001));
        assertThat(report.efficiency().averageEstimatedPromptCostUsd()).isCloseTo(0.325 / 3.0, within(0.0001));

        assertThat(report.retrieval().primaryVariant()).isEqualTo(HybridEvalReport.Variant.HYBRID_RERANK);
        assertThat(report.retrieval().variants()).containsKeys(
                HybridEvalReport.Variant.DENSE_ONLY,
                HybridEvalReport.Variant.LEXICAL_ONLY,
                HybridEvalReport.Variant.HYBRID,
                HybridEvalReport.Variant.HYBRID_RERANK);
        assertThat(report.notes()).contains("平均 token 成本当前按 prompt 估算，不含 completion token。");
    }

    private void writeTrace(ObjectMapper objectMapper, Path path, TurnTrace trace) throws Exception {
        objectMapper.writeValue(path.toFile(), trace);
    }

    private TurnTrace successTrace(String turnId,
                                   String sessionId,
                                   long startTimeMs,
                                   long totalLatencyMs,
                                   long firstNodeLatencyMs,
                                   List<Integer> promptTokens) {
        return trace(turnId, sessionId, "SUCCESS", startTimeMs, totalLatencyMs, firstNodeLatencyMs, promptTokens);
    }

    private TurnTrace errorTrace(String turnId,
                                 String sessionId,
                                 long startTimeMs,
                                 long totalLatencyMs,
                                 long firstNodeLatencyMs,
                                 List<Integer> promptTokens) {
        return trace(turnId, sessionId, "ERROR", startTimeMs, totalLatencyMs, firstNodeLatencyMs, promptTokens);
    }

    private TurnTrace trace(String turnId,
                            String sessionId,
                            String status,
                            long startTimeMs,
                            long totalLatencyMs,
                            long firstNodeLatencyMs,
                            List<Integer> promptTokens) {
        TurnTrace trace = new TurnTrace();
        trace.setTurnId(turnId);
        trace.setSessionId(sessionId);
        trace.setTraceId("trace-" + turnId);
        trace.setStatus(status);
        trace.setStartTimeMs(startTimeMs);
        trace.setTotalLatencyMs(totalLatencyMs);

        int order = 1;
        for (Integer tokenCount : promptTokens) {
            TurnTrace.PromptCall promptCall = new TurnTrace.PromptCall();
            promptCall.setOrder(order++);
            promptCall.setStage("agent-main");
            promptCall.setEstimatedTokens(tokenCount);
            promptCall.setStatus("SENT");
            trace.getPrompts().add(promptCall);
        }
        TurnTrace.Node node = new TurnTrace.Node();
        node.setOrder(order);
        node.setNodeName("streaming");
        node.setType("streaming");
        node.setLatencyMs(firstNodeLatencyMs);
        node.setStatus("SUCCESS");
        trace.getNodes().add(node);
        return trace;
    }

    private static final class EmptySearcher implements RagRecall.DocumentSearcher {

        @Override
        public List<RagRecall.RecallHit> searchByText(String sessionId, String subQueryText, int size) {
            return List.of();
        }

        @Override
        public List<RagRecall.RecallHit> searchByVector(String sessionId, String textToEmbed, int size) {
            return List.of();
        }
    }

    private static final class PassThroughReranker implements RagReranker {

        @Override
        public List<RagRecall.RecallHit> rerank(String query, List<RagRecall.RecallHit> candidates, int topN) {
            return candidates.stream().limit(topN).toList();
        }
    }
}
