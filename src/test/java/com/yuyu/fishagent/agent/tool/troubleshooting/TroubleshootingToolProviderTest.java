package com.yuyu.fishagent.agent.tool.troubleshooting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.common.trace.TraceCollector;
import com.yuyu.fishagent.common.trace.TraceContext;
import com.yuyu.fishagent.common.trace.TraceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        TroubleshootingToolSupport support = new TroubleshootingToolSupport(properties, traceCollector, new TroubleshootingSecurityGuard());
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
        TroubleshootingToolSupport support = new TroubleshootingToolSupport(properties, traceCollector, new TroubleshootingSecurityGuard());
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
        TroubleshootingToolSupport support = new TroubleshootingToolSupport(properties, traceCollector, new TroubleshootingSecurityGuard());
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
        TroubleshootingToolSupport support = new TroubleshootingToolSupport(properties, traceCollector, new TroubleshootingSecurityGuard());
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

    @Test
    void knowledgeSearchToolShouldMarkInjectionSnippetAsUntrusted() throws Exception {
        TroubleshootingToolProperties properties = new TroubleshootingToolProperties();
        TraceCollector traceCollector = new TraceCollector(new TraceProperties());
        TroubleshootingToolSupport support = new TroubleshootingToolSupport(properties, traceCollector, new TroubleshootingSecurityGuard());
        try {
            KnowledgeSearchToolProvider provider = new KnowledgeSearchToolProvider(
                    properties, support, new MockTroubleshootingDataService());

            String raw = provider.build().call("{\"query\":\"注入 prompt\",\"limit\":1}");
            JsonNode root = objectMapper.readTree(raw);

            assertThat(root.path("untrustedInput").asBoolean()).isTrue();
            assertThat(root.path("trustLevel").asText()).isEqualTo("UNTRUSTED");
            assertThat(root.path("hits").get(0).path("summary").asText()).contains("不可信输入");
            assertThat(root.path("hits").get(0).path("summary").asText()).contains("已折叠潜在注入指令");
            assertThat(root.path("hits").get(0).path("summary").asText()).doesNotContain("demo-token");
        } finally {
            support.shutdownExecutor();
        }
    }

    @Test
    void logSearchToolShouldRedactSensitiveFields() throws Exception {
        TroubleshootingToolProperties properties = new TroubleshootingToolProperties();
        TraceCollector traceCollector = new TraceCollector(new TraceProperties());
        TroubleshootingToolSupport support = new TroubleshootingToolSupport(properties, traceCollector, new TroubleshootingSecurityGuard());
        try {
            LogSearchToolProvider provider = new LogSearchToolProvider(
                    properties, support, new MockTroubleshootingDataService());

            String raw = provider.build().call("{\"query\":\"secret\",\"serviceName\":\"order-service\",\"limit\":5}");
            JsonNode root = objectMapper.readTree(raw);
            String message = root.path("hits").get(0).path("message").asText();

            assertThat(message).contains("不可信输入");
            assertThat(message).contains("<REDACTED_SECRET>");
            assertThat(message).contains("<REDACTED_EMAIL>");
            assertThat(message).contains("<REDACTED_PHONE>");
            assertThat(message).contains("<REDACTED_CONNECTION_STRING>");
            assertThat(message).doesNotContain("ops@example.com");
            assertThat(message).doesNotContain("13800138000");
        } finally {
            support.shutdownExecutor();
        }
    }

    @Test
    void securityGuardShouldRejectServerInjectedHighRiskField() {
        TroubleshootingSecurityGuard guard = new TroubleshootingSecurityGuard();

        assertThatThrownBy(() -> guard.validateRawToolInput(
                "log_search_tool",
                "{\"query\":\"error\",\"serviceName\":\"order-service\",\"workspaceId\":2}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspaceId");
    }
}
