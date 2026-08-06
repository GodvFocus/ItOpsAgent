package com.ai.itops.rag.pipeline.recall;

import com.ai.itops.rag.config.KnowledgeProperties;
import com.ai.itops.rag.config.MilvusProperties;
import com.ai.itops.rag.config.RagProperties;
import com.ai.itops.auth.context.UserContextHolder;
import io.milvus.client.MilvusServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

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
 * <p>邻块通过 Milvus 的标量条件按文档和 chunk 坐标读取，不改变中心命中的排序分数。</p>
 */
@Slf4j
public class ContextExpander {

    private final RagProperties ragProperties;
    private final ObjectProvider<MilvusServiceClient> milvusProvider;
    private final ObjectProvider<io.milvus.v2.client.MilvusClientV2> milvusV2Provider;
    private final MilvusProperties milvusProperties;

    public ContextExpander(RagProperties ragProperties,
                           KnowledgeProperties knowledgeProperties) {
        this(ragProperties, knowledgeProperties, null, null, null);
    }

    public ContextExpander(RagProperties ragProperties,
                           KnowledgeProperties knowledgeProperties,
                           ObjectProvider<MilvusServiceClient> milvusProvider,
                           MilvusProperties milvusProperties) {
        this(ragProperties, knowledgeProperties, milvusProvider, null, milvusProperties);
    }

    public ContextExpander(RagProperties ragProperties,
                           KnowledgeProperties knowledgeProperties,
                           ObjectProvider<MilvusServiceClient> milvusProvider,
                           ObjectProvider<io.milvus.v2.client.MilvusClientV2> milvusV2Provider,
                           MilvusProperties milvusProperties) {
        this.ragProperties = ragProperties;
        this.milvusProvider = milvusProvider;
        this.milvusV2Provider = milvusV2Provider;
        this.milvusProperties = milvusProperties;
    }

    /**
     * 邻块扩展：只处理带有稳定文档坐标的知识库命中，记忆和卡片命中保持原样。
     */
    public List<RagRecall.RecallHit> expand(List<RagRecall.RecallHit> hits) {
        RagProperties.ExpandNeighbors cfg = ragProperties.getExpandNeighbors();
        if (hits == null || hits.isEmpty() || !cfg.isEnabled()) {
            return hits == null ? List.of() : hits;
        }
        MilvusServiceClient client = milvusProvider == null ? null : milvusProvider.getIfAvailable();
        if (client == null || milvusProperties == null) {
            return hits;
        }
        int span = Math.max(0, cfg.getNeighborSpan());
        if (span == 0) {
            return hits;
        }
        Long userId = UserContextHolder.currentUserIdOrNull();
        String workspaceId = UserContextHolder.currentWorkspaceIdOrNull();
        if (workspaceId == null || workspaceId.isBlank()) {
            return hits;
        }
        List<RagRecall.RecallHit> out = new java.util.ArrayList<>(hits.size());
        for (RagRecall.RecallHit hit : hits) {
            if (!expandable(hit)) {
                out.add(hit);
                continue;
            }
            boolean publicScope = "公开".equals(hit.effectiveSourceLabel())
                    || "官方".equals(hit.effectiveSourceLabel());
            try {
                List<MilvusKnowledgeChunkSupport.ChunkRow> rows = MilvusKnowledgeChunkSupport.scanDocument(
                        client,
                        milvusV2Provider == null ? null : milvusV2Provider.getIfAvailable(),
                        milvusProperties,
                        hit.docId(),
                        workspaceId,
                        userId,
                        publicScope,
                        Math.max(0, hit.chunkIndex() - span),
                        hit.chunkIndex() + span,
                        span * 2 + 1,
                        Math.max(1, span * 2 + 1));
                List<RagRecall.RecallHit> neighbors = rows.stream()
                        .map(row -> new RagRecall.RecallHit(
                                row.id(), row.content(), hit.score(), hit.source(), hit.effectiveSourceLabel(),
                                row.authority() == null ? hit.authority() : row.authority(),
                                row.createdAt() == null ? hit.createdAt() : row.createdAt(),
                                row.docId(), row.chunkIndex(), row.docName()))
                        .toList();
                out.add(mergeNeighbors(hit, neighbors));
            } catch (Exception e) {
                log.debug("[ContextExpander] 邻块扩展失败 id={}, docId={}: {}", hit.id(), hit.docId(), e.getMessage());
                out.add(hit);
            }
        }
        return out;
    }

    private static boolean expandable(RagRecall.RecallHit hit) {
        if (hit == null || hit.docId() == null || hit.docId().isBlank() || hit.chunkIndex() == null
                || hit.chunkIndex() < 0) {
            return false;
        }
        String label = hit.effectiveSourceLabel();
        return !"记忆".equals(label) && !"卡片".equals(label);
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
