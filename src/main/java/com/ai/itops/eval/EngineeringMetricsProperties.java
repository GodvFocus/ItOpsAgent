package com.ai.itops.eval;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 真实工程指标评测配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.eval.engineering")
public class EngineeringMetricsProperties {

    /** 排障 golden set 路径；默认跟随主资源，便于在生产环境直接触发。 */
    private String troubleshootingGoldenSetPath = "classpath:eval/golden-troubleshooting.json";

    /** prompt token 单价（USD / 1K tokens）；用于估算平均模型成本。 */
    private double promptTokenCostUsdPer1k = 0.0;
}
