package com.ai.itops.chat.router;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Query Router 配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.chat.router")
public class QueryRouterProperties {

    /**
     * 是否启用显式 Router；关闭时一律回退到 Agent 路径，避免静默行为漂移。
     */
    private boolean enabled = true;

    /**
     * 复杂排障信号达到该分数后，进入 Agent 路径。
     */
    private int agentScoreThreshold = 3;
}
