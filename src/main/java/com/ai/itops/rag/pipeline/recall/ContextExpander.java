package com.ai.itops.rag.pipeline.recall;

import com.ai.itops.rag.config.KnowledgeProperties;
import com.ai.itops.rag.config.RagProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命中文档切片的邻块扩展器。
 *
 * <p>检索排序仍只看中心命中；扩展阶段只把同一文档前后 N 个 chunk 拼回渲染内容，缓解答案缺少上下文的问题。
 * 对话记忆和知识卡片没有稳定 chunk 坐标，默认跳过。</p>
 *
 * <p>TODO: ES → Milvus 迁移后邻块扩展暂不可用，等待 Milvus Collection 建立 chunk 级索引后恢复。</p>
 */
@Slf4j
public class ContextExpander {

    private final RagProperties ragProperties;

    public ContextExpander(RagProperties ragProperties,
                           KnowledgeProperties knowledgeProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * 邻块扩展：ES → Milvus 迁移期间直接返回原始命中，不做扩展。
     * TODO: Milvus Collection 支持 doc_id + chunk_index 标量过滤后恢复邻块 fetch 逻辑。
     */
    public List<RagRecall.RecallHit> expand(List<RagRecall.RecallHit> hits) {
        RagProperties.ExpandNeighbors cfg = ragProperties.getExpandNeighbors();
        if (hits == null || hits.isEmpty() || !cfg.isEnabled()) {
            return hits == null ? List.of() : hits;
        }
        log.debug("[ContextExpander] ES 已迁移至 Milvus，邻块扩展暂不可用，直接返回原始命中");
        return hits;
    }

    /**
     * 合并中心命中与其邻块内容，生成扩展后的 merged 内容。
     * <p>静态工具方法，不依赖 ES。</p>
     */
    static RagRecall.RecallHit mergeNeighbors(RagRecall.RecallHit center, List<RagRecall.RecallHit> neighbors) {
        Map<String, RagRecall.RecallHit> byKey = new LinkedHashMap<>();
        if (neighbors != null) {
            for (RagRecall.RecallHit hit : neighbors) {
                String key = RagRecall.dedupKey(hit);
                if (key != null) {
                    byKey.putIfAbsent(key, hit);
                }
            }
        }
        String centerKey = RagRecall.dedupKey(center);
        if (centerKey != null) {
            byKey.putIfAbsent(centerKey, center);
        }
        List<RagRecall.RecallHit> ordered = byKey.values().stream()
                .sorted(Comparator.comparing(
                        RagRecall.RecallHit::chunkIndex,
                        Comparator.nullsLast(Integer::compareTo)))
                .toList();
        StringBuilder text = new StringBuilder();
        for (RagRecall.RecallHit hit : ordered) {
            if (hit.content() == null || hit.content().isBlank()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(hit.content().trim());
        }
        return center.withContent(text.isEmpty() ? center.content() : text.toString());
    }
}
