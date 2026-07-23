package com.yuyu.fishagent.memory.longterm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 长期事实 embedding 余弦查重工具。
 * <p>ES kNN 查重逻辑已随 ES 迁移至 Milvus 而移除，Milvus 版查重已内置在 {@link MilvusLongTermMemoryStore} 中。
 * 保留静态 cosine 方法供向量相似度计算使用。</p>
 */
@Slf4j
@Component
public class LongTermMemoryDeduplicator {

    /**
     * 计算两个等长向量的余弦相似度。
     *
     * @param a 向量 a
     * @param b 向量 b
     * @return 余弦相似度，范围 [-1, 1]；向量长度不一致或包含零向量时返回 0.0
     */
    public static double cosine(List<Float> a, List<Float> b) {
        if (a == null || b == null || a.size() != b.size() || a.isEmpty()) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            double x = a.get(i);
            double y = b.get(i);
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
