package com.yuyu.fishagent.agent.tool.troubleshooting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.common.trace.TraceCollector;
import com.yuyu.fishagent.common.trace.TraceContext;
import com.yuyu.fishagent.common.trace.TraceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TroubleshootingToolProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
    }

    @Test
    void knowledgeSearchToolShouldRespectLimitAndWriteAuditTrace() throws Exception {
        TroubleshootingToolProperties properties = new TroubleshootingToolProperties();
        TraceCollector traceCollector = new TraceCollector(new TraceProperties());
        TroubleshootingToolSupport support = new TroubleshootingToolSupport(properties, traceCollector);
        try {
            KnowledgeSearchToolProvider provider = new KnowledgeSearchToolProvider(
                    properties, support, new MockTroubleshootingDataService());
            traceCollector.startTurn("turn-knowledge", "sid", "trace");
            TraceContext.setTurnId("turn-knowledge");

            String raw = provider.build().call("{\"query\":\"ECONNRESET order-service\",\"limit\":1}");
            JsonNode root = objectMapper.readTree(raw);

            assertThat(root.path("ok").asBoolean()).isTrue();
            assertThat(root.path("total").asInt()).isEqualTo(1);
            assertThat(root.path("hits")).hasSize(1);
            assertThat(traceCollector.current("turn-knowledge").getNodes())
                    .anySatisfy(node -> assertThat(node.getNodeName()).isEqualTo("tool-audit"));
        } finally {
            support.shutdownExecutor();
        }
    }

    @Test
    void logSearchToolShouldFilterByWindowAndLevel() throws Exception {
        TroubleshootingToolProperties properties = new TroubleshootingToolProperties();
        TraceCollector traceCollector = new TraceCollector(new TraceProperties());
        TroubleshootingToolSupport support = new TroubleshootingToolSupport(properties, traceCollector);
        try {
            LogSearchToolProvider provider = new LogSearchToolProvider(
                    properties, support, new MockTroubleshootingDataService());

            String raw = provider.build().call("""
                    {
                      "query":"RedisCommandTimeoutException",
                      "serviceName":"order-service",
                      "level":"ERROR",
                      "startTime":"2026-07-25T10:20:00+08:00",
                      "endTime":"2026-07-25T10:30:00+08:00",
                      "limit":5
                    }
                    """);
            JsonNode root = objectMapper.readTree(raw);

            assertThat(root.path("ok").asBoolean()).isTrue();
            assertThat(root.path("hits")).hasSize(1);
            assertThat(root.path("hits").get(0).path("message").asText()).contains("RedisCommandTimeoutException");
        } finally {
            support.shutdownExecutor();
        }
    }

    @Test
    void serviceStatusToolShouldRespectWindowAndLimit() throws Exception {
        TroubleshootingToolProperties properties = new TroubleshootingToolProperties();
        TraceCollector traceCollector = new TraceCollector(new TraceProperties());
        TroubleshootingToolSupport support = new TroubleshootingToolSupport(properties, traceCollector);
        try {
            ServiceStatusToolProvider provider = new ServiceStatusToolProvider(
                    properties, support, new MockTroubleshootingDataService());

            String raw = provider.build().call("""
                    {
                      "serviceName":"order-service",
                      "environment":"prod",
                      "startTime":"2026-07-25T10:00:00+08:00",
                      "endTime":"2026-07-25T10:40:00+08:00",
                      "limit":1
                    }
                    """);
            JsonNode root = objectMapper.readTree(raw);

            assertThat(root.path("ok").asBoolean()).isTrue();
            assertThat(root.path("total").asInt()).isEqualTo(1);
            assertThat(root.path("statuses")).hasSize(1);
            assertThat(root.path("statuses").get(0).path("status").asText()).isEqualTo("DEGRADED");
        } finally {
            support.shutdownExecutor();
        }
    }

    @Test
    void troubleshootingToolSupportShouldReturnTimeoutFallback() {
        TroubleshootingToolProperties properties = new TroubleshootingToolProperties();
        properties.setTimeoutMs(1);
        TraceCollector traceCollector = new TraceCollector(new TraceProperties());
        TroubleshootingToolSupport support = new TroubleshootingToolSupport(properties, traceCollector);
        try {
            traceCollector.startTurn("turn-timeout", "sid", "trace");
            TraceContext.setTurnId("turn-timeout");

            String result = support.execute(
                    "timeout_tool",
                    "request",
                    () -> {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "ok";
                    },
                    value -> value,
                    error -> error
            );

            assertThat(result).contains("timeout after 1ms");
            assertThat(traceCollector.current("turn-timeout").getNodes())
                    .anySatisfy(node -> {
                        assertThat(node.getNodeName()).isEqualTo("tool-audit");
                        assertThat(node.getStatus()).isEqualTo("ERROR");
                    });
        } finally {
            support.shutdownExecutor();
        }
    }
}
