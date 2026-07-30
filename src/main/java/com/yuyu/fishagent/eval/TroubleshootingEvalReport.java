package com.yuyu.fishagent.eval;

import java.util.List;

/**
 * 排障最小评测报告。
 */
public record TroubleshootingEvalReport(int caseCount,
                                        double routeAccuracy,
                                        double routeF1,
                                        double recallAtK,
                                        double exactTokenHitRate,
                                        double citationAccuracy,
                                        double citationCoverage,
                                        double toolSelectionAccuracy,
                                        double toolParameterAccuracy,
                                        double averageToolCalls,
                                        int unauthorizedRecallCount,
                                        List<CaseResult> cases) {

    public record CaseResult(String id,
                             String category,
                             String expectedRoute,
                             String actualRoute,
                             boolean routeMatched,
                             double recallAtK,
                             double exactTokenHitRate,
                             double citationAccuracy,
                             double citationCoverage,
                             double toolSelectionAccuracy,
                             double toolParameterAccuracy,
                             int toolCalls,
                             int unauthorizedRecallCount,
                             List<String> actualTools,
                             List<String> rankedIds) {
    }
}
