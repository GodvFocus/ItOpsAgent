package com.ai.itops.memory.longterm;

import com.ai.itops.memory.config.MemoryProperties;
import com.ai.itops.rag.config.MilvusProperties;
import com.ai.itops.rag.pipeline.recall.MilvusNativeBm25Support;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import io.milvus.v2.client.MilvusClientV2;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 基于 Milvus 的长期事实存储。
 * <p>作为 ES → Milvus 迁移后的长期记忆写入入口，替代原 ES 版长期记忆存储。
 * 写入前通过 Milvus ANN 向量相似度查重，冲突事实走 supersede 逻辑（物理删除旧记录，写入新记录）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusLongTermMemoryStore implements LongTermMemoryStore {

    private final MilvusServiceClient milvusClient;
    private final MilvusClientV2 milvusClientV2;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final MemoryProperties properties;
    private final MilvusProperties milvusProperties;
    private final MemoryConflictJudge conflictJudge;

    @Override
    public void saveFacts(Long userId, String sessionId, List<String> facts) {
        if (!properties.isLongTermEnabled()) {
            log.debug("[MilvusLongTermMemory] 长期记忆未启用，跳过写入 sid={}", sessionId);
            return;
        }
        if (userId == null) {
            log.debug("[MilvusLongTermMemory] userId 为空，跳过写入 sid={}", sessionId);
            return;
        }
        if (facts == null || facts.isEmpty()) {
            log.debug("[MilvusLongTermMemory] facts 为空，跳过写入 sid={}", sessionId);
            return;
        }

        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            log.warn("[MilvusLongTermMemory] EmbeddingModel 不可用，跳过写入");
            return;
        }

        String collection = memoryCollection();
        long now = System.currentTimeMillis();

        for (String fact : facts) {
            if (fact == null || fact.isBlank()) {
                continue;
            }
            try {
                String normalizedFact = fact.trim();
                List<Float> embedding = toFloatList(embeddingModel.embed(normalizedFact));

                // 查重：用 Milvus ANN 找到最近邻的已有事实
                List<SimilarFact> similarFacts = findSimilarFacts(
                        collection, String.valueOf(userId), embedding);

                MemoryWriteDecision decision = decideMemoryWrite(normalizedFact, similarFacts);
                if (decision.type() == MemoryDecision.DROP_DUPLICATE) {
                    log.debug("[MilvusLongTermMemory] 跳过重复事实 sid={}, factLen={}",
                            sessionId, normalizedFact.length());
                    continue;
                }
                if (decision.type() == MemoryDecision.SUPERSEDE_AND_WRITE) {
                    supersedeConflicts(collection, decision.conflicts(), now);
                }

                // 写入新事实
                String id = UUID.randomUUID().toString();
                List<InsertParam.Field> fields = Arrays.asList(
                        new InsertParam.Field("id", List.of(id)),
                        new InsertParam.Field("user_id", List.of(String.valueOf(userId))),
                        new InsertParam.Field("content", List.of(normalizedFact)),
                        new InsertParam.Field("embedding", List.of(embedding)),
                        new InsertParam.Field("source_type", List.of("chat")),
                        new InsertParam.Field("superseded", List.of(false)),
                        new InsertParam.Field("created_at", List.of(now))
                );
                milvusClient.insert(InsertParam.newBuilder()
                        .withCollectionName(collection)
                        .withFields(fields)
                        .build());
                log.debug("[MilvusLongTermMemory] 事实写入完成 id={}, factLen={}", id, normalizedFact.length());
            } catch (Exception e) {
                log.warn("[MilvusLongTermMemory] 写入失败 sid={}: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * 通过 Milvus ANN 搜索与候选事实向量最相似的历史事实。
     *
     * @param collection Milvus Collection 名
     * @param userId     当前用户 ID 字符串
     * @param embedding  候选事实的向量表示
     * @return 相似历史事实列表（仅含余弦相似度 >= 阈值的记录）
     */
    private List<SimilarFact> findSimilarFacts(String collection, String userId,
                                                List<Float> embedding) {
        MemoryProperties.Dedup dedupCfg = properties.getDedup();
        if (!dedupCfg.isEnabled() || embedding == null || embedding.isEmpty()) {
            return List.of();
        }
        try {
            int k = Math.max(1, dedupCfg.getK());
            String expr = String.format(
                    "user_id == \"%s\" and source_type == \"chat\" and superseded == false", userId);
            SearchParam search = SearchParam.newBuilder()
                    .withCollectionName(collection)
                    .withVectors(List.of(embedding))
                    .withVectorFieldName("embedding")
                    .withTopK(k)
                    .withMetricType(io.milvus.param.MetricType.COSINE)
                    .withExpr(expr)
                    .withParams(String.format("{\"nprobe\":%d}", Math.max(8, dedupCfg.getNumCandidates())))
                    .withOutFields(List.of("id", "content", "created_at"))
                    .build();
            SearchResultsWrapper results = new SearchResultsWrapper(
                    milvusClient.search(search).getData().getResults());

            List<SimilarFact> similar = new ArrayList<>();
            List<SearchResultsWrapper.IDScore> scores = results.getIDScore(0);
            if (scores != null) {
                for (int i = 0; i < scores.size(); i++) {
                    double score = scores.get(i).getScore();
                    // 仅保留超过相似度阈值的结果
                    if (score < dedupCfg.getSimilarityThreshold()) {
                        continue;
                    }
                    String id = String.valueOf(scores.get(i).getStrID());
                    List<?> contentList = results.getFieldData("content", i);
                    String content = contentList != null && !contentList.isEmpty()
                            ? String.valueOf(contentList.get(0)) : "";
                    if (content.isBlank()) {
                        continue;
                    }
                    List<?> createdAtList = results.getFieldData("created_at", i);
                    long createdAt = createdAtList != null && !createdAtList.isEmpty()
                            && createdAtList.get(0) instanceof Number
                            ? ((Number) createdAtList.get(0)).longValue() : 0L;
                    similar.add(new SimilarFact(id, content.trim(), createdAt, score));
                }
            }
            if (!similar.isEmpty()) {
                log.debug("[MilvusLongTermMemory] 查重命中相似事实 count={}, threshold={}",
                        similar.size(), dedupCfg.getSimilarityThreshold());
            }
            return similar;
        } catch (Exception e) {
            log.warn("[MilvusLongTermMemory] 相似事实查询失败，按无相似处理: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 将冲突的旧事实从 Milvus 中物理删除。
     * <p>注意：与 ES 版的「标记失效」不同，Milvus 版采用物理删除以简化实现；若需要审计回滚能力可后续改为标记失效。</p>
     */
    private void supersedeConflicts(String collection, List<SimilarFact> conflicts, long now) {
        for (SimilarFact similar : conflicts) {
            try {
                milvusClient.delete(DeleteParam.newBuilder()
                        .withCollectionName(collection)
                        .withExpr(String.format("id == \"%s\"", similar.id()))
                        .build());
                log.debug("[MilvusLongTermMemory] 冲突旧事实已删除 id={}, validTo={}", similar.id(), now);
            } catch (Exception e) {
                log.warn("[MilvusLongTermMemory] 删除冲突旧事实失败 id={}: {}", similar.id(), e.getMessage());
            }
        }
    }

    /**
     * 根据相似事实列表判断候选事实的写入策略。
     * <p>规则：
     * <ol>
     *   <li>无相似事实 → 直接写入</li>
     *   <li>冲突判定关闭 → 视为重复，跳过</li>
     *   <li>任一条被判为 SAME → 重复，跳过</li>
     *   <li>存在 CONFLICT → 覆盖旧事实后写入新事实</li>
     *   <li>其余 → 不冲突，直接写入</li>
     * </ol>
     */
    private MemoryWriteDecision decideMemoryWrite(String candidateFact,
                                                   List<SimilarFact> similarFacts) {
        if (similarFacts == null || similarFacts.isEmpty()) {
            return new MemoryWriteDecision(MemoryDecision.WRITE_NEW, List.of());
        }
        if (!properties.getConflict().isEnabled()) {
            return new MemoryWriteDecision(MemoryDecision.DROP_DUPLICATE, List.of());
        }

        List<SimilarFact> conflicts = new ArrayList<>();
        for (SimilarFact similar : similarFacts) {
            MemoryConflictJudge.Verdict verdict = conflictJudge.judge(candidateFact, similar);
            if (verdict == MemoryConflictJudge.Verdict.SAME) {
                return new MemoryWriteDecision(MemoryDecision.DROP_DUPLICATE, List.of());
            }
            if (verdict == MemoryConflictJudge.Verdict.CONFLICT) {
                conflicts.add(similar);
            }
        }
        return conflicts.isEmpty()
                ? new MemoryWriteDecision(MemoryDecision.WRITE_NEW, List.of())
                : new MemoryWriteDecision(MemoryDecision.SUPERSEDE_AND_WRITE, conflicts);
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float v : vector) {
            values.add(v);
        }
        return values;
    }

    private String memoryCollection() {
        return milvusProperties.getBm25().isEnabled()
                && MilvusNativeBm25Support.serverSupportsBm25(milvusClientV2.getServerVersion())
                ? milvusProperties.getBm25().getUserMemoryCollection()
                : properties.getLongTermIndexName();
    }

    /**
     * 长期记忆写入决策类型。
     */
    private enum MemoryDecision {
        /** 直接写入新事实 */
        WRITE_NEW,
        /** 判定为重复，丢弃不写入 */
        DROP_DUPLICATE,
        /** 存在冲突，覆盖旧事实后写入新事实 */
        SUPERSEDE_AND_WRITE
    }

    /**
     * 写入决策及其冲突列表。
     */
    private record MemoryWriteDecision(MemoryDecision type, List<SimilarFact> conflicts) {
    }
}
