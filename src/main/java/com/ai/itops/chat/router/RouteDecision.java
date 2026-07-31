package com.ai.itops.chat.router;

/**
 * 查询路由决策。
 *
 * @param route  路由结果
 * @param reason 路由原因，供 trace 与排障使用
 */
public record RouteDecision(QueryRoute route, String reason) {

    public static RouteDecision fastRag(String reason) {
        return new RouteDecision(QueryRoute.FAST_RAG, reason);
    }

    public static RouteDecision troubleshootingAgent(String reason) {
        return new RouteDecision(QueryRoute.TROUBLESHOOTING_AGENT, reason);
    }

    public boolean agentPath() {
        return route == QueryRoute.TROUBLESHOOTING_AGENT;
    }
}
