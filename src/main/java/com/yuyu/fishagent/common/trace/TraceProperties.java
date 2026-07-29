package com.yuyu.fishagent.common.trace;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent turn trace 配置，对应 {@code fish.trace.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.trace")
public class TraceProperties {

    /** 总开关；关闭后主链路只保留原有指标，不采集/写入 turn trace。 */
    private boolean enabled = true;

    /** Trace 文件存储目录。 */
    private String storageDir = "data/traces";

    /** 表名（MySQL 存储时使用，预留）。 */
    private String tableName = "itops_turn_trace";

    /** 单条 trace 文档最大字符数，超出时 writer 会截断片段字段。 */
    private int docMaxChars = 50_000;

    /** 采样率，0.0-1.0。 */
    private double sampleRate = 1.0;

    /** 单个片段字段最大字符数，避免 trace 存全文。 */
    private int snippetMaxChars = 200;

}
