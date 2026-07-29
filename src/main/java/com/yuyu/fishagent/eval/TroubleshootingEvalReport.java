package com.yuyu.fishagent.eval;

import java.util.List;

/**
 * 排障最小评测报告。
 */
public record TroubleshootingEvalReport(int caseCount,
                                        double routeAccuracy,
                                        double recallAtK,
                                        double exactTokenHitRate,
                                        double citationAccuracy,
                                        double citationCoverage,
                                        double toolSelectionAccuracy,
                                        double averageToolCalls,
                                        int unauthorizedRecallCount,
                                        List<CaseResult> cases) {

    public record CaseResult(String id,
                             String category,
                             boolean routeMatched,
                             double recallAtK,
                             double exactTokenHitRate,
                             double citationAccuracy,
                             double citationCoverage,
                             double toolSelectionAccuracy,
                             int toolCalls,
                             int unauthorizedRecallCount,
                             List<String> actualTools,
                             List<String> rankedIds) {
    }
}
