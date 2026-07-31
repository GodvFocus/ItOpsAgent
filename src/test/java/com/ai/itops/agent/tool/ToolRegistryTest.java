package com.ai.itops.agent.tool;

import com.ai.itops.agent.config.ToolProperties;
import com.ai.itops.agent.tool.troubleshooting.TroubleshootingSecurityGuard;
import com.ai.itops.agent.tool.result.LargeResultScratchStore;
import com.ai.itops.agent.tool.result.ToolResultBudgeter;
import com.ai.itops.agent.tool.result.ToolResultGovernor;
import com.ai.itops.agent.tool.result.ToolResultProperties;
import com.ai.itops.agent.tool.result.ToolResultSummarizer;
import com.ai.itops.common.trace.TraceCollector;
import com.ai.itops.common.trace.TraceProperties;
import com.ai.itops.chat.router.QueryRoute;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRegistryTest {

    @Test
    void governResultKeepsFinalOutputWithinConfiguredLimit() throws Exception {
        ToolProperties properties = new ToolProperties();
        properties.setMaxResultChars(120);
        properties.setHintThresholdChars(20);
        properties.setOverrides(java.util.Map.of("web_fetch", 120));
        ToolRegistry registry = new ToolRegistry(List.of(), properties);

        Method method = ToolRegistry.class.getDeclaredMethod("governResult", String.class, String.class);
        method.setAccessible(true);
        String result = (String) method.invoke(registry, "web_fetch", "x".repeat(500));

        assertThat(result).hasSizeLessThanOrEqualTo(120);
    }

    @Test
    void turnBoundCallbackAppliesScratchGovernance() {
        ToolResultProperties resultProperties = new ToolResultProperties();
        resultProperties.setBudgetTokens(120);
        resultProperties.setScratchLargeThresholdTokens(150);
        resultProperties.setScratchChunkTokens(50);
        resultProperties.setSummarizeThresholdTokens(10_000);
        TraceCollector traceCollector = new TraceCollector(new TraceProperties());
        traceCollector.startTurn("turn-1", "sid", "trace-1");
        ToolResultGovernor governor = new ToolResultGovernor(
                resultProperties,
                new ToolResultBudgeter(),
                mock(ToolResultSummarizer.class),
                newScratchStore(resultProperties),
                traceCollector,
                null);
        AgentToolProvider provider = new AgentToolProvider() {
            @Override
            public String name() {
                return "huge_log";
            }

            @Override
            public ToolCallback build() {
                return new ToolCallback() {
                    @Override
                    public String call(String toolInput) {
                        return "error 500\n".repeat(500);
                    }

                    @Override
                    public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                        return null;
                    }
                };
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(provider), new ToolProperties(), governor);
        registry.init();

        String result = registry.allCallbacks("turn-1").get(0).call("{}");

        assertThat(result).contains("search_large_result");
        assertThat(traceCollector.current("turn-1").getNodes())
                .anySatisfy(node -> assertThat(node.getDisposition()).isEqualTo("retrieved"));
    }

    @SuppressWarnings("unchecked")
    private LargeResultScratchStore newScratchStore(ToolResultProperties properties) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new LargeResultScratchStore(provider, new ObjectMapper(), properties);
    }

    @Test
    void troubleshootingToolShouldRejectUnexpectedHighRiskFieldBeforeExecution() {
        AgentToolProvider provider = new AgentToolProvider() {
            @Override
            public String name() {
                return "log_search_tool";
            }

            @Override
            public ToolCallback build() {
                return new ToolCallback() {
                    @Override
                    public String call(String toolInput) {
                        return "{\"ok\":true}";
                    }

                    @Override
                    public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                        return null;
                    }
                };
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(provider), new ToolProperties(), null, new TroubleshootingSecurityGuard());
        registry.init();

        assertThatThrownBy(() -> registry.allCallbacks().get(0)
                .call("{\"query\":\"error\",\"workspaceId\":2}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspaceId");
    }

    @Test
    void routeCallbacksShouldExposeOnlyWhitelistedToolsAndRecheckAtCallTime() {
        AgentToolProvider allowed = simpleProvider("log_search_tool");
        AgentToolProvider denied = simpleProvider("file_write");
        ToolProperties properties = new ToolProperties();
        properties.setRouteAllowlist(Map.of(
                "TROUBLESHOOTING_AGENT", List.of("log_search_tool")));
        ToolRegistry registry = new ToolRegistry(List.of(allowed, denied), properties);
        registry.init();

        List<ToolCallback> routeCallbacks = registry.allCallbacks(QueryRoute.TROUBLESHOOTING_AGENT, "turn-1");
        assertThat(routeCallbacks).hasSize(1);

        properties.setRouteAllowlist(Map.of("TROUBLESHOOTING_AGENT", List.of()));
        assertThatThrownBy(() -> routeCallbacks.get(0).call("{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not allowed");
    }

    private AgentToolProvider simpleProvider(String name) {
        return new AgentToolProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolCallback build() {
                return new ToolCallback() {
                    @Override
                    public String call(String toolInput) {
                        return "{}";
                    }

                    @Override
                    public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                        return null;
                    }
                };
            }
        };
    }
}
