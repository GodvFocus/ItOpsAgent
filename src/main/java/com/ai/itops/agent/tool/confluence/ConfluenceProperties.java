package com.ai.itops.agent.tool.confluence;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Confluence 只读连接配置。
 *
 * <p>默认关闭且不提供地址或凭证，避免配置未完成时影响现有 Agent 启动。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.tools.confluence")
public class ConfluenceProperties {

    /** 是否启用 Confluence 工具。 */
    private boolean enabled = false;

    /** Confluence 根地址，可包含 Cloud 的 /wiki 上下文路径。 */
    private String baseUrl = "";

    /** 认证类型：NONE、BEARER 或 BASIC。 */
    private String authType = "BEARER";

    /** Bearer/API Token，禁止写入普通日志。 */
    private String token = "";

    /** Basic 认证用户名。 */
    private String username = "";

    /** Basic 认证密码或应用密码。 */
    private String password = "";

    /** 允许检索的空间 Key，多个值使用逗号分隔；为空表示使用账号可见范围。 */
    private String spaceKeys = "";

    /** 单次搜索默认返回条数。 */
    private int defaultSearchLimit = 5;

    /** 单次搜索允许的最大返回条数。 */
    private int maxSearchLimit = 10;

    /** 单次请求连接与读取超时。 */
    private long timeoutMs = 3000;

    /** 页面正文最大字符数。 */
    private int maxContentChars = 12000;

    /** 搜索 query 最大字符数。 */
    private int maxQueryChars = 512;

    public boolean isConfigured() {
        if (!enabled || baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String type = authType == null ? "NONE" : authType.trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "NONE" -> true;
            case "BEARER", "API_TOKEN" -> token != null && !token.isBlank();
            case "BASIC" -> username != null && !username.isBlank()
                    && password != null && !password.isBlank();
            default -> false;
        };
    }
}
