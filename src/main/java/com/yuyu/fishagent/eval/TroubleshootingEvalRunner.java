package com.yuyu.fishagent.eval;

import com.yuyu.fishagent.agent.tool.troubleshooting.MockTroubleshootingDataService;
import com.yuyu.fishagent.agent.tool.troubleshooting.TroubleshootingSecurityGuard;
import com.yuyu.fishagent.agent.tool.troubleshooting.TroubleshootingToolSupport;
import com.yuyu.fishagent.agent.tool.troubleshooting.TroubleshootingToolProperties;
import com.yuyu.fishagent.chat.router.HeuristicQueryRouter;
import com.yuyu.fishagent.chat.router.QueryRoute;
import com.yuyu.fishagent.chat.router.QueryRouter;
import com.yuyu.fishagent.chat.router.QueryRouterProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 排障场景离线评测 runner。
 *
 * <p>它复用当前 Query Router、模拟排障数据和安全守卫，评估最小闭环是否成立。</p>
 */
public class TroubleshootingEvalRunner {

    private final QueryRouter queryRouter;
    private final MockTroubleshootingDataService dataService;
    private final TroubleshootingSecurityGuard securityGuard;

    public TroubleshootingEvalRunner() {
        QueryRouterProperties routerProperties = new QueryRouterProperties();
        routerProperties.setEnabled(true);
        routerProperties.setAgentScoreThreshold(3);
        this.queryRouter = new HeuristicQueryRouter(routerProperties);
        this.dataService = new MockTroubleshootingDataService();
        this.securityGuard = new TroubleshootingSecurityGuard();
    }

    public TroubleshootingEvalReport run(TroubleshootingGoldenSet goldenSet, int k) {
        List<TroubleshootingGoldenSet.Case> cases = goldenSet == null ? List.of() : goldenSet.cases();
        if (cases.isEmpty()) {
            return new TroubleshootingEvalReport(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, List.of());
        }
        List<TroubleshootingEvalReport.CaseResult> results = new ArrayList<>();
        double routeAccuracy = 0.0;
        double routeF1 = 0.0;
        double recallAtK = 0.0;
        double exactTokenHitRate = 0.0;
        double citationAccuracy = 0.0;
        double citationCoverage = 0.0;
        double toolSelectionAccuracy = 0.0;
        double toolParameterAccuracy = 0.0;
        double averageToolCalls = 0.0;
        int unauthorizedRecallCount = 0;
        int truePositive = 0;
        int falsePositive = 0;
        int falseNegative = 0;

        for (TroubleshootingGoldenSet.Case item : cases) {
            TroubleshootingEvalReport.CaseResult result = evaluateCase(item, k);
            results.add(result);
            routeAccuracy += result.routeMatched() ? 1.0 : 0.0;
            if ("TROUBLESHOOTING_AGENT".equals(result.actualRoute())
                    && "TROUBLESHOOTING_AGENT".equals(result.expectedRoute())) {
                truePositive++;
            } else if ("TROUBLESHOOTING_AGENT".equals(result.actualRoute())) {
                falsePositive++;
            } else if ("TROUBLESHOOTING_AGENT".equals(result.expectedRoute())) {
                falseNegative++;
            }
            recallAtK += result.recallAtK();
            exactTokenHitRate += result.exactTokenHitRate();
            citationAccuracy += result.citationAccuracy();
            citationCoverage += result.citationCoverage();
            toolSelectionAccuracy += result.toolSelectionAccuracy();
            toolParameterAccuracy += result.toolParameterAccuracy();
            averageToolCalls += result.toolCalls();
            unauthorizedRecallCount += result.unauthorizedRecallCount();
        }

        int count = cases.size();
        routeF1 = f1(truePositive, falsePositive, falseNegative);
        return new TroubleshootingEvalReport(
                count,
                routeAccuracy / count,
                routeF1,
                recallAtK / count,
                exactTokenHitRate / count,
                citationAccuracy / count,
                citationCoverage / count,
                toolSelectionAccuracy / count,
                toolParameterAccuracy / count,
                averageToolCalls / count,
                unauthorizedRecallCount,
                List.copyOf(results)
        );
    }

    private TroubleshootingEvalReport.CaseResult evaluateCase(TroubleshootingGoldenSet.Case item, int k) {
        QueryRoute actualRoute = queryRouter.route(item.routeInput()).route();
        String actualRouteName = actualRoute.name();
        String expectedRoute = item.expectedRoute();
        boolean routeMatched = expectedRoute == null || expectedRoute.equals(actualRouteName);
        boolean troubleshootingPath = actualRoute == QueryRoute.TROUBLESHOOTING_AGENT;
        List<String> actualTools = new ArrayList<>();
        List<String> rankedIds = new ArrayList<>();
        List<String> evidenceTexts = new ArrayList<>();

        if (item.knowledgeRequest() != null) {
            if (troubleshootingPath) {
                actualTools.add("knowledge_search_tool");
            }
            TroubleshootingGoldenSet.KnowledgeRequest request = item.knowledgeRequest();
            dataService.searchKnowledge(
                            request.query(),
                            request.serviceName(),
                            window(request.startTime(), request.endTime()),
                            limit(request.limit()))
                    .forEach(doc -> {
                        rankedIds.add(doc.id());
                        evidenceTexts.add(doc.title());
                        evidenceTexts.add(doc.summary());
                        evidenceTexts.add(doc.content());
                    });
        }
        if (item.statusRequest() != null) {
            if (troubleshootingPath) {
                actualTools.add("service_status_tool");
            }
            TroubleshootingGoldenSet.StatusRequest request = item.statusRequest();
            dataService.searchServiceStatus(
                            request.serviceName(),
                            request.environment(),
                            window(request.startTime(), request.endTime()),
                            limit(request.limit()))
                    .forEach(status -> {
                        evidenceTexts.add(status.summary());
                        evidenceTexts.addAll(status.symptoms());
                        evidenceTexts.addAll(status.indicators());
                    });
        }
        if (item.logRequest() != null) {
            if (troubleshootingPath) {
                actualTools.add("log_search_tool");
            }
            TroubleshootingGoldenSet.LogRequest request = item.logRequest();
            dataService.searchLogs(
                            request.query(),
                            request.serviceName(),
                            request.level(),
                            window(request.startTime(), request.endTime()),
                            limit(request.limit()))
                    .forEach(log -> {
                        rankedIds.add(log.id());
                        evidenceTexts.add(log.path());
                        evidenceTexts.add(log.message());
                    });
        }

        Set<String> relevantIds = item.relevantIds() == null ? Set.of() : new LinkedHashSet<>(item.relevantIds());
        double recallAtK = recallAtK(rankedIds, relevantIds, k);
        double tokenHitRate = tokenHitRate(evidenceTexts, item.expectedTokens());
        double citationAccuracy = citationAccuracy(rankedIds, relevantIds, k);
        double citationCoverage = citationCoverage(rankedIds, relevantIds, k);
        double toolSelectionAccuracy = expectedToolSet(item.expectedTools()).equals(new LinkedHashSet<>(actualTools)) ? 1.0 : 0.0;
        double toolParameterAccuracy = toolParameterAccuracy(item, actualTools);
        int unauthorizedCount = unauthorizedRecallCount(item);
        return new TroubleshootingEvalReport.CaseResult(
                item.id(),
                item.category(),
                expectedRoute,
                actualRouteName,
                routeMatched,
                recallAtK,
                tokenHitRate,
                citationAccuracy,
                citationCoverage,
                toolSelectionAccuracy,
                toolParameterAccuracy,
                actualTools.size(),
                unauthorizedCount,
                List.copyOf(actualTools),
                List.copyOf(rankedIds)
        );
    }

    private TroubleshootingToolSupport.TimeWindow window(String startTime, String endTime) {
        TroubleshootingToolProperties properties = new TroubleshootingToolProperties();
        return new TroubleshootingToolSupport(properties, nullTraceCollector(), securityGuard)
                .resolveWindow(startTime, endTime);
    }

    private int limit(Integer limit) {
        TroubleshootingToolProperties properties = new TroubleshootingToolProperties();
        return Math.min(limit == null || limit <= 0 ? properties.getDefaultLimit() : limit, properties.getMaxLimit());
    }

    private int unauthorizedRecallCount(TroubleshootingGoldenSet.Case item) {
        if (item.forbiddenToolName() == null || item.forbiddenToolInput() == null || item.forbiddenToolInput().isBlank()) {
            return 0;
        }
        try {
            securityGuard.validateRawToolInput(item.forbiddenToolName(), item.forbiddenToolInput());
            return 1;
        } catch (IllegalArgumentException ignore) {
            return 0;
        }
    }

    private double recallAtK(List<String> rankedIds, Set<String> relevantIds, int k) {
        if (relevantIds.isEmpty()) {
            return 1.0;
        }
        int hits = 0;
        int limit = Math.min(Math.max(1, k), rankedIds.size());
        for (int i = 0; i < limit; i++) {
            if (relevantIds.contains(rankedIds.get(i))) {
                hits++;
            }
        }
        return hits / (double) relevantIds.size();
    }

    private double tokenHitRate(List<String> evidenceTexts, List<String> expectedTokens) {
        if (expectedTokens == null || expectedTokens.isEmpty()) {
            return 1.0;
        }
        String corpus = String.join("\n", evidenceTexts).toLowerCase(Locale.ROOT);
        int matched = 0;
        for (String token : expectedTokens) {
            if (token != null && !token.isBlank() && corpus.contains(token.toLowerCase(Locale.ROOT))) {
                matched++;
            }
        }
        return matched / (double) expectedTokens.size();
    }

    private double citationAccuracy(List<String> rankedIds, Set<String> relevantIds, int k) {
        if (relevantIds.isEmpty() && rankedIds.isEmpty()) {
            return 1.0;
        }
        int expectedCitationCount = relevantIds.isEmpty() ? Math.max(1, k) : relevantIds.size();
        int limit = Math.min(Math.max(1, Math.min(k, expectedCitationCount)), rankedIds.size());
        if (limit == 0) {
            return 0.0;
        }
        int hits = 0;
        for (int i = 0; i < limit; i++) {
            if (relevantIds.contains(rankedIds.get(i))) {
                hits++;
            }
        }
        return hits / (double) limit;
    }

    private double citationCoverage(List<String> rankedIds, Set<String> relevantIds, int k) {
        if (relevantIds.isEmpty()) {
            return 1.0;
        }
        int limit = Math.min(Math.max(1, k), rankedIds.size());
        int hits = 0;
        for (int i = 0; i < limit; i++) {
            if (relevantIds.contains(rankedIds.get(i))) {
                hits++;
            }
        }
        return hits / (double) relevantIds.size();
    }

    private Set<String> expectedToolSet(List<String> expectedTools) {
        return expectedTools == null ? Set.of() : new LinkedHashSet<>(expectedTools);
    }

    private double toolParameterAccuracy(TroubleshootingGoldenSet.Case item, List<String> actualTools) {
        Set<String> actualToolSet = new LinkedHashSet<>(actualTools);
        Set<String> expectedToolSet = expectedToolSet(item.expectedTools());
        int total = 3;
        int matched = 0;
        matched += parameterScore("knowledge_search_tool", expectedToolSet.contains("knowledge_search_tool"), actualToolSet);
        matched += parameterScore("service_status_tool", expectedToolSet.contains("service_status_tool"), actualToolSet);
        matched += parameterScore("log_search_tool", expectedToolSet.contains("log_search_tool"), actualToolSet);
        return matched / (double) total;
    }

    private int parameterScore(String toolName, boolean expectedRequest, Set<String> actualToolSet) {
        if (!expectedRequest) {
            return actualToolSet.contains(toolName) ? 0 : 1;
        }
        return actualToolSet.contains(toolName) ? 1 : 0;
    }

    private double f1(int truePositive, int falsePositive, int falseNegative) {
        double precisionDenominator = truePositive + falsePositive;
        double recallDenominator = truePositive + falseNegative;
        double precision = precisionDenominator == 0 ? 0.0 : truePositive / precisionDenominator;
        double recall = recallDenominator == 0 ? 0.0 : truePositive / recallDenominator;
        if (precision + recall == 0.0) {
            return 0.0;
        }
        return 2 * precision * recall / (precision + recall);
    }

    private com.yuyu.fishagent.common.trace.TraceCollector nullTraceCollector() {
        return new com.yuyu.fishagent.common.trace.TraceCollector(new com.yuyu.fishagent.common.trace.TraceProperties());
    }
}
