package com.yuyu.fishagent.eval;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 评测入口配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.eval.rag")
public class RagEvalProperties {

    /** 默认 golden set，可通过环境变量替换为外部文件。 */
    private String goldenSetPath = "classpath:eval/golden-rag.json";

    /** 默认评测截断位置。 */
    private int k = 5;

    /** 每条召回腿的候选数量。 */
    private int perLegK = 10;
}
