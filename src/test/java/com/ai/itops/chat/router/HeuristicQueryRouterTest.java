package com.ai.itops.chat.router;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicQueryRouterTest {

    private final HeuristicQueryRouter router = new HeuristicQueryRouter(new QueryRouterProperties());

    @Test
    void shouldRouteRunbookQuestionToFastRag() {
        RouteDecision decision = router.route("order-service 重启 SOP 是什么？");

        assertThat(decision.route()).isEqualTo(QueryRoute.FAST_RAG);
        assertThat(decision.reason()).contains("sop");
    }

    @Test
    void shouldRouteStatusAndLogsTroubleshootingToAgent() {
        RouteDecision decision = router.route("order-service 状态正常，但 /api/v1/order/create 报 ECONNRESET，帮我结合日志排查原因");

        assertThat(decision.route()).isEqualTo(QueryRoute.TROUBLESHOOTING_AGENT);
        assertThat(decision.reason()).contains("api-path");
        assertThat(decision.reason()).contains("error-code");
    }
}
