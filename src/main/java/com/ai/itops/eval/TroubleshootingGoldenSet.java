package com.ai.itops.eval;

import java.util.List;

/**
 * 最小排障评测集。
 *
 * <p>目标不是重放完整在线链路，而是在开发阶段用稳定样例校验：
 * 路由是否正确、工具是否选对、关键 token 是否命中、以及越权参数是否被拦截。</p>
 */
public record TroubleshootingGoldenSet(List<Case> cases) {

    public TroubleshootingGoldenSet {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public record Case(String id,
                       String category,
                       String routeInput,
                       String expectedRoute,
                       List<String> expectedTools,
                       KnowledgeRequest knowledgeRequest,
                       StatusRequest statusRequest,
                       LogRequest logRequest,
                       List<String> relevantIds,
                       List<String> expectedTokens,
                       String forbiddenToolName,
                       String forbiddenToolInput) {
    }

    public record KnowledgeRequest(String query,
                                   String serviceName,
                                   String startTime,
                                   String endTime,
                                   Integer limit) {
    }

    public record StatusRequest(String serviceName,
                                String environment,
                                String startTime,
                                String endTime,
                                Integer limit) {
    }

    public record LogRequest(String query,
                             String serviceName,
                             String level,
                             String startTime,
                             String endTime,
                             Integer limit) {
    }
}
