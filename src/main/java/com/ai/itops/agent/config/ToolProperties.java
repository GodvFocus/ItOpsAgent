package com.ai.itops.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部工具的统一配置入口，对应 {@code fish.tools.*}。
 * <p>
 * 各子配置项是否启用由其对应工具 {@code @ConditionalOnProperty} 自行判断，缺失 key 时不装配。
 * 工具结果治理也放在这里，避免新增同名配置 Bean 并保持工具配置边界集中。
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.tools")
public class ToolProperties {

    /** 全局默认工具返回字符上限。 */
    private int maxResultChars = 4000;

    /** 超过此阈值时追加提示，引导模型先提取关键信息。 */
    private int hintThresholdChars = 1500;

    /** 各工具可覆盖默认上限，key 为工具名（小写下划线）。 */
    private Map<String, Integer> overrides = new HashMap<>(Map.of(
            "web_fetch", 6000,
            "file_read", 8000,
            "web_search", 3000,
            "confluence_search_tool", 4000,
            "confluence_fetch_tool", 12000
    ));

    /**
     * 路由工具白名单。空列表表示该路由不允许调用工具，未配置的路由默认保留兼容行为。
     */
    private Map<String, List<String>> routeAllowlist = new LinkedHashMap<>(Map.of(
            "FAST_RAG", List.of(),
            "TROUBLESHOOTING_AGENT", List.of(
                    "knowledge_search_tool",
                    "log_search_tool",
                    "service_status_tool",
                    "confluence_search_tool",
                    "confluence_fetch_tool",
                    "search_large_result")
    ));

    private Tavily tavily = new Tavily();
    private Bocha bocha = new Bocha();
    private Amap amap = new Amap();

    @Data
    public static class Tavily {
        private String apiKey;
        private String baseUrl = "https://api.tavily.com";
        private int maxResults = 5;
    }

    @Data
    public static class Bocha {
        private String apiKey;
        private String baseUrl = "https://api.bochaai.com";
        private int count = 5;
    }

    @Data
    public static class Amap {
        private String key;
        private String baseUrl = "https://restapi.amap.com";
    }

    public int getMaxResultChars(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return maxResultChars;
        }
        return overrides.getOrDefault(toolName, maxResultChars);
    }

    public boolean isToolAllowed(String route, String toolName) {
        if (route == null || route.isBlank()) {
            return true;
        }
        List<String> allowed = routeAllowlist.get(route.trim().toUpperCase());
        if (allowed == null) {
            return true;
        }
        return allowed.contains("*") || allowed.contains(toolName);
    }
}
