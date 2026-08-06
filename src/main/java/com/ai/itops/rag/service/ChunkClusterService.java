package com.ai.itops.rag.service;

import com.ai.itops.card.entity.KnowledgeCard;
import com.ai.itops.card.mapper.KnowledgeCardMapper;
import com.ai.itops.rag.config.MilvusProperties;
import com.ai.itops.rag.dto.ChunkGroupItemVO;
import com.ai.itops.rag.dto.ChunkGroupVO;
import com.ai.itops.rag.dto.ChunkItemVO;
import com.ai.itops.rag.dto.ChunkListVO;
import com.ai.itops.rag.dto.RelatedCardVO;
import com.ai.itops.rag.dto.RelatedChunkVO;
import com.ai.itops.rag.entity.DocumentMetadata;
import com.ai.itops.rag.mapper.DocumentMetadataMapper;
import com.ai.itops.rag.pipeline.recall.MilvusKnowledgeChunkSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.v2.client.MilvusClientV2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 知识库切片可视化服务。
 *
 * <p>切片数据来自 Milvus，聚类只在请求时使用内存中的确定性 K-Means；Redis 只缓存分组索引和摘要，
 * 不缓存原文和权限结果。卡片没有单独的向量 collection，因此卡片关联使用现有 EmbeddingModel 计算。</p>
 */
@Slf4j
@Service
public class ChunkClusterService {

    private static final String CACHE_PREFIX = "chunk-cluster:";
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final int CLUSTER_MIN_CHUNKS = 10;
    private static final int MAX_GROUPS = 8;
    private static final int MAX_CHUNKS = 10_000;
    private static final int KMEANS_ITERATIONS = 20;
    private static final double RELATED_THRESHOLD = 0.70;

    private final DocumentMetadataMapper documentMetadataMapper;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<MilvusServiceClient> milvusProvider;
    private final ObjectProvider<MilvusClientV2> milvusV2Provider;
    private final MilvusProperties milvusProperties;
    private final KnowledgeCardMapper knowledgeCardMapper;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    public ChunkClusterService(DocumentMetadataMapper documentMetadataMapper,
                               ObjectProvider<StringRedisTemplate> redisProvider,
                               ObjectMapper objectMapper) {
        this(documentMetadataMapper, redisProvider, objectMapper, null, null, null, null, null);
    }

    @Autowired
    public ChunkClusterService(DocumentMetadataMapper documentMetadataMapper,
                               ObjectProvider<StringRedisTemplate> redisProvider,
                               ObjectMapper objectMapper,
                               ObjectProvider<MilvusServiceClient> milvusProvider,
                               ObjectProvider<MilvusClientV2> milvusV2Provider,
                               MilvusProperties milvusProperties,
                               KnowledgeCardMapper knowledgeCardMapper,
                               ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.documentMetadataMapper = documentMetadataMapper;
        this.redisProvider = redisProvider;
        this.objectMapper = objectMapper;
        this.milvusProvider = milvusProvider;
        this.milvusV2Provider = milvusV2Provider;
        this.milvusProperties = milvusProperties;
        this.knowledgeCardMapper = knowledgeCardMapper;
        this.embeddingModelProvider = embeddingModelProvider;
    }

    /** 返回文档的主题分组；切片不足时返回一个“全部切片”分组。 */
    public ChunkGroupVO getGroups(String taskId, Long currentUserId, String currentWorkspaceId, boolean admin) {
        DocumentMetadata row = loadVisibleSuccessDocument(taskId, currentUserId, currentWorkspaceId, admin);
        ClusterPayload payload = loadClusterPayload(row, currentWorkspaceId);
        List<ChunkGroupItemVO> groups = payload.groups().stream()
                .map(group -> new ChunkGroupItemVO(group.groupIndex(), group.title(), group.chunkIndexes().size()))
                .toList();
        int total = payload.groups().stream().mapToInt(group -> group.chunkIndexes().size()).sum();
        return new ChunkGroupVO(row.getTaskId(), row.getFileName(), payload.summary(), total, groups);
    }

    /** 返回分组过滤、关键词过滤和分页后的切片。 */
    public ChunkListVO getChunks(String taskId, Integer groupIndex, String keyword, int page, int size,
                                 Long currentUserId, String currentWorkspaceId, boolean admin) {
        DocumentMetadata row = loadVisibleSuccessDocument(taskId, currentUserId, currentWorkspaceId, admin);
        ClusterPayload payload = loadClusterPayload(row, currentWorkspaceId);
        Set<Integer> allowed = allowedChunkIndexes(payload, groupIndex);
        String normalizedKeyword = normalizeKeyword(keyword);
        Predicate<MilvusKnowledgeChunkSupport.ChunkRow> matches = chunk ->
                (groupIndex == null || allowed.contains(chunk.chunkIndex()))
                        && (normalizedKeyword == null
                        || chunk.content().toLowerCase(java.util.Locale.ROOT)
                        .contains(normalizedKeyword.toLowerCase(java.util.Locale.ROOT)));

        List<MilvusKnowledgeChunkSupport.ChunkRow> chunks = loadChunks(row, currentWorkspaceId).stream()
                .filter(matches)
                .sorted(Comparator.comparing(MilvusKnowledgeChunkSupport.ChunkRow::chunkIndex))
                .toList();
        int pageSize = Math.min(100, Math.max(1, size));
        int currentPage = Math.max(1, page);
        int from = Math.min(chunks.size(), (currentPage - 1) * pageSize);
        int to = Math.min(chunks.size(), from + pageSize);
        List<ChunkItemVO> records = chunks.subList(from, to).stream()
                .map(chunk -> new ChunkItemVO(chunk.chunkIndex(), chunk.content(), chunk.content().length(),
                        countRelatedCards(row, chunk)))
                .toList();
        return new ChunkListVO(row.getTaskId(), records, (long) chunks.size());
    }

    /** 查询某个切片最相近的当前用户已确认卡片。 */
    public List<RelatedCardVO> getRelatedCards(String taskId, Integer chunkIndex,
                                               Long currentUserId, String currentWorkspaceId, boolean admin) {
        DocumentMetadata row = loadVisibleSuccessDocument(taskId, currentUserId, currentWorkspaceId, admin);
        if (chunkIndex == null || chunkIndex < 0) {
            return List.of();
        }
        MilvusKnowledgeChunkSupport.ChunkRow chunk = loadChunks(row, currentWorkspaceId).stream()
                .filter(item -> Objects.equals(item.chunkIndex(), chunkIndex))
                .findFirst().orElse(null);
        return chunk == null ? List.of() : findRelatedCards(row.getUserId(), chunk.embedding(), 5);
    }

    /** 卡片详情页反向查找同一源文档中最相近的切片。 */
    public List<RelatedChunkVO> findRelatedChunksForCard(KnowledgeCard card) {
        if (card == null || card.getId() == null
                || !KnowledgeCard.SOURCE_KNOWLEDGE.equals(card.getSourceType())
                || card.getSourceId() == null || card.getSourceId().isBlank()) {
            return List.of();
        }
        DocumentMetadata row = documentMetadataMapper.selectOne(Wrappers.<DocumentMetadata>lambdaQuery()
                .eq(DocumentMetadata::getTaskId, card.getSourceId().trim()));
        if (row == null || !DocumentMetadata.STATUS_SUCCESS.equals(row.getStatus())
                || !Objects.equals(row.getUserId(), card.getUserId())) {
            return List.of();
        }
        List<Float> vector = embed(cardText(card));
        if (vector.isEmpty()) {
            return List.of();
        }
        String workspaceId = row.getWorkspaceId() == null || row.getWorkspaceId().isBlank()
                ? "default" : row.getWorkspaceId();
        List<MilvusKnowledgeChunkSupport.ScoredChunkRow> hits = MilvusKnowledgeChunkSupport.searchByVector(
                milvusProvider == null ? null : milvusProvider.getIfAvailable(),
                milvusV2Provider == null ? null : milvusV2Provider.getIfAvailable(),
                milvusProperties,
                vector,
                row.getTaskId(),
                workspaceId,
                row.getUserId(),
                DocumentMetadata.VISIBILITY_WORKSPACE.equalsIgnoreCase(row.getVisibility()),
                5,
                16);
        return hits.stream()
                .filter(hit -> hit.score() >= RELATED_THRESHOLD)
                .map(hit -> new RelatedChunkVO(row.getTaskId(), row.getFileName(), hit.row().chunkIndex(),
                        preview(hit.row().content(), 180), round(hit.score())))
                .toList();
    }

    /** 清除指定文档的聚类缓存。 */
    public void evictClusterCache(String taskId) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null && taskId != null && !taskId.isBlank()) {
            redis.delete(CACHE_PREFIX + taskId.trim());
        }
    }

    private ClusterPayload loadClusterPayload(DocumentMetadata row, String workspaceId) {
        String key = CACHE_PREFIX + row.getTaskId();
        ClusterPayload cached = readCache(key);
        if (cached != null) {
            return cached;
        }
        List<MilvusKnowledgeChunkSupport.ChunkRow> chunks = loadChunks(row, workspaceId);
        ClusterPayload payload = buildClusterPayload(row, chunks);
        writeCache(key, payload);
        return payload;
    }

    private ClusterPayload buildClusterPayload(DocumentMetadata row,
                                               List<MilvusKnowledgeChunkSupport.ChunkRow> chunks) {
        List<MilvusKnowledgeChunkSupport.ChunkRow> valid = chunks.stream()
                .filter(chunk -> chunk.embedding() != null && !chunk.embedding().isEmpty())
                .toList();
        if (chunks.size() < CLUSTER_MIN_CHUNKS || valid.size() < CLUSTER_MIN_CHUNKS) {
            return new ClusterPayload(row.getTaskId(), buildSummary(row, chunks),
                    List.of(new ClusterItem(0, "全部切片", chunks.stream()
                            .map(MilvusKnowledgeChunkSupport.ChunkRow::chunkIndex).sorted().toList())));
        }

        int k = Math.min(MAX_GROUPS, Math.max(2, valid.size() / 5));
        int[] labels = ChunkKMeansClusterer.cluster(valid.stream()
                .map(MilvusKnowledgeChunkSupport.ChunkRow::embedding).toList(), k, KMEANS_ITERATIONS);
        Map<Integer, List<Integer>> byGroup = new LinkedHashMap<>();
        for (int i = 0; i < labels.length; i++) {
            byGroup.computeIfAbsent(labels[i], ignored -> new ArrayList<>()).add(valid.get(i).chunkIndex());
        }
        chunks.stream()
                .filter(chunk -> chunk.embedding() == null || chunk.embedding().isEmpty())
                .forEach(chunk -> byGroup.computeIfAbsent(0, ignored -> new ArrayList<>()).add(chunk.chunkIndex()));

        Map<Integer, MilvusKnowledgeChunkSupport.ChunkRow> byIndex = chunks.stream()
                .collect(java.util.stream.Collectors.toMap(MilvusKnowledgeChunkSupport.ChunkRow::chunkIndex,
                        item -> item, (left, right) -> left, LinkedHashMap::new));
        List<ClusterItem> groups = new ArrayList<>();
        int displayIndex = 0;
        for (List<Integer> indexes : byGroup.values()) {
            List<Integer> sorted = indexes.stream().distinct().sorted().toList();
            String sample = sorted.stream().map(byIndex::get).filter(Objects::nonNull)
                    .map(MilvusKnowledgeChunkSupport.ChunkRow::content).findFirst().orElse("");
            groups.add(new ClusterItem(displayIndex++, preview(sample, 16), sorted));
        }
        return new ClusterPayload(row.getTaskId(), buildSummary(row, chunks), groups);
    }

    private List<MilvusKnowledgeChunkSupport.ChunkRow> loadChunks(DocumentMetadata row, String workspaceId) {
        String actualWorkspace = workspaceId == null || workspaceId.isBlank()
                ? row.getWorkspaceId() : workspaceId;
        if (actualWorkspace == null || actualWorkspace.isBlank()) {
            actualWorkspace = "default";
        }
        boolean publicScope = DocumentMetadata.VISIBILITY_WORKSPACE.equalsIgnoreCase(row.getVisibility());
        return MilvusKnowledgeChunkSupport.scanDocument(
                milvusProvider == null ? null : milvusProvider.getIfAvailable(),
                milvusV2Provider == null ? null : milvusV2Provider.getIfAvailable(),
                milvusProperties,
                row.getTaskId(), actualWorkspace, row.getUserId(), publicScope,
                null, null, MAX_CHUNKS, 256);
    }

    private int countRelatedCards(DocumentMetadata row, MilvusKnowledgeChunkSupport.ChunkRow chunk) {
        return findRelatedCards(row.getUserId(), chunk.embedding(), 5).size();
    }

    private List<RelatedCardVO> findRelatedCards(Long userId, List<Float> vector, int limit) {
        if (userId == null || vector == null || vector.isEmpty() || knowledgeCardMapper == null) {
            return List.of();
        }
        List<KnowledgeCard> cards = knowledgeCardMapper.selectList(Wrappers.<KnowledgeCard>lambdaQuery()
                .eq(KnowledgeCard::getUserId, userId)
                .eq(KnowledgeCard::getStatus, KnowledgeCard.STATUS_CONFIRMED));
        List<RelatedCardScore> scored = new ArrayList<>();
        for (KnowledgeCard card : cards) {
            List<Float> cardVector = embed(cardText(card));
            double similarity = MilvusKnowledgeChunkSupport.cosine(vector, cardVector);
            if (similarity >= RELATED_THRESHOLD) {
                scored.add(new RelatedCardScore(card, similarity));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(RelatedCardScore::similarity).reversed()
                        .thenComparing(score -> score.card().getId()))
                .limit(Math.max(1, limit))
                .map(score -> new RelatedCardVO(score.card().getId(), score.card().getTitle(),
                        score.card().getCardType(), round(score.similarity())))
                .toList();
    }

    private List<Float> embed(String text) {
        EmbeddingModel model = embeddingModelProvider == null ? null : embeddingModelProvider.getIfAvailable();
        if (model == null || text == null || text.isBlank()) {
            return List.of();
        }
        try {
            float[] vector = model.embed(text);
            List<Float> out = new ArrayList<>(vector.length);
            for (float value : vector) {
                out.add(value);
            }
            return out;
        } catch (Exception e) {
            log.debug("[ChunkCluster] 卡片向量生成失败: {}", e.getMessage());
            return List.of();
        }
    }

    private DocumentMetadata loadVisibleSuccessDocument(String taskId, Long currentUserId,
                                                        String currentWorkspaceId, boolean admin) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        DocumentMetadata row = documentMetadataMapper.selectOne(Wrappers.<DocumentMetadata>lambdaQuery()
                .eq(DocumentMetadata::getTaskId, taskId.trim())
                .eq(DocumentMetadata::getWorkspaceId, currentWorkspaceId));
        if (row == null) {
            throw new ResponseStatusException(NOT_FOUND, "文档不存在");
        }
        boolean owner = currentUserId != null && Objects.equals(currentUserId, row.getUserId());
        boolean workspaceVisible = DocumentMetadata.VISIBILITY_WORKSPACE.equalsIgnoreCase(row.getVisibility())
                && currentWorkspaceId != null && Objects.equals(currentWorkspaceId, row.getWorkspaceId());
        if (!admin && !owner && !workspaceVisible) {
            throw new ResponseStatusException(FORBIDDEN, "无权查看该文档切片");
        }
        if (!DocumentMetadata.STATUS_SUCCESS.equals(row.getStatus())) {
            throw new ResponseStatusException(BAD_REQUEST, "文档尚未解析完成");
        }
        return row;
    }

    private ClusterPayload readCache(String key) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return null;
        }
        try {
            String raw = redis.opsForValue().get(key);
            return raw == null ? null : objectMapper.readValue(raw, ClusterPayload.class);
        } catch (Exception e) {
            log.warn("[ChunkCluster] Redis 缓存读取失败 key={}: {}", key, e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, ClusterPayload payload) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(payload), CACHE_TTL);
        } catch (Exception e) {
            log.warn("[ChunkCluster] Redis 缓存写入失败 key={}: {}", key, e.getMessage());
        }
    }

    private static Set<Integer> allowedChunkIndexes(ClusterPayload payload, Integer groupIndex) {
        if (groupIndex == null) {
            return payload.groups().stream().flatMap(group -> group.chunkIndexes().stream())
                    .collect(java.util.stream.Collectors.toSet());
        }
        return payload.groups().stream().filter(group -> Objects.equals(group.groupIndex(), groupIndex))
                .findFirst().map(group -> Set.copyOf(group.chunkIndexes())).orElse(Set.of());
    }

    private static String buildSummary(DocumentMetadata row, List<MilvusKnowledgeChunkSupport.ChunkRow> chunks) {
        String sample = chunks.stream().sorted(Comparator.comparing(MilvusKnowledgeChunkSupport.ChunkRow::chunkIndex))
                .limit(5).map(MilvusKnowledgeChunkSupport.ChunkRow::content)
                .map(content -> preview(content, 80)).collect(java.util.stream.Collectors.joining("；"));
        if (sample.isBlank()) {
            return "暂无可用于生成摘要的切片内容。";
        }
        int count = row.getChunkCount() == null ? chunks.size() : row.getChunkCount();
        return "文档「" + row.getFileName() + "」包含 " + count + " 个切片，主要内容包括：" + preview(sample, 180);
    }

    private static String cardText(KnowledgeCard card) {
        return (card.getTitle() == null ? "" : card.getTitle()) + "\n"
                + (card.getContent() == null ? "" : card.getContent());
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String value = keyword.trim();
        return value.isBlank() ? null : value;
    }

    private static String preview(String content, int limit) {
        String value = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return value.length() <= limit ? value : value.substring(0, limit) + "...";
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record RelatedCardScore(KnowledgeCard card, double similarity) {
    }

    public record ClusterPayload(String taskId, String summary, List<ClusterItem> groups) {
    }

    public record ClusterItem(Integer groupIndex, String title, List<Integer> chunkIndexes) {
    }
}
