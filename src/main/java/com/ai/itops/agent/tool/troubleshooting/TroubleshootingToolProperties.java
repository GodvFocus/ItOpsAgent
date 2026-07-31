package com.ai.itops.agent.tool.troubleshooting;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 排障工具配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.tools.troubleshooting")
public class TroubleshootingToolProperties {

    /**
     * 是否启用排障工具集。
     */
    private boolean enabled = true;

    /**
     * 单次工具调用超时时间。
     */
    private long timeoutMs = 1500;

    /**
     * 默认返回条数。
     */
    private int defaultLimit = 5;

    /**
     * 单次调用允许的最大返回条数。
     */
    private int maxLimit = 10;

    /**
     * 未显式传时间范围时的默认回看窗口（小时）。
     */
    private int defaultLookbackHours = 168;

    /**
     * 允许的最大时间窗口（小时）。
     */
    private int maxWindowHours = 720;
}
