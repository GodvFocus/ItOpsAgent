package com.ai.itops.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.itops.card.entity.KnowledgeCard;
import com.ai.itops.rag.dto.ChunkGroupItemVO;
import com.ai.itops.rag.dto.ChunkGroupVO;
import com.ai.itops.rag.dto.ChunkItemVO;
import com.ai.itops.rag.dto.ChunkListVO;
import com.ai.itops.rag.dto.RelatedCardVO;
import com.ai.itops.rag.dto.RelatedChunkVO;
import com.ai.itops.rag.entity.DocumentMetadata;
import com.ai.itops.rag.mapper.DocumentMetadataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 知识库切片可视化服务。
 *
 * <p>职责边界：Controller 只做 HTTP 参数接入；本服务负责权限校验、切片读取、向量聚类、Redis 缓存和切片↔卡片动态关联。</p>
 *
 * <p>TODO: ES → Milvus 迁移后，切片聚类和关联功能暂不可用，待 Milvus Collection 提供 chunk-level ANN 和标量过滤后恢复。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkClusterService {

    private static final String CACHE_PREFIX = "chunk-cluster:";
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final DocumentMetadataMapper documentMetadataMapper;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper objectMapper;

    /**
     * TODO: ES → Milvus 迁移后恢复切片聚类分组功能。
     */
    public ChunkGroupVO getGroups(String taskId, Long currentUserId, String currentWorkspaceId, boolean admin) {
        DocumentMetadata row = loadVisibleSuccessDocument(taskId, currentUserId, currentWorkspaceId, admin);
        return new ChunkGroupVO(row.getTaskId(), row.getFileName(),
                "ES 已迁移至 Milvus，切片聚类暂不可用。", 0, List.of());
    }

    /**
     * TODO: ES → Milvus 迁移后恢复切片列表查询功能。
     */
    public ChunkListVO getChunks(String taskId, Integer groupIndex, String keyword, int page, int size,
                                 Long currentUserId, String currentWorkspaceId, boolean admin) {
        loadVisibleSuccessDocument(taskId, currentUserId, currentWorkspaceId, admin);
        return new ChunkListVO(taskId, List.of(), 0L);
    }

    /**
     * TODO: ES → Milvus 迁移后恢复切片关联卡片查询功能。
     */
    public List<RelatedCardVO> getRelatedCards(String taskId, Integer chunkIndex,
                                               Long currentUserId, String currentWorkspaceId, boolean admin) {
        loadVisibleSuccessDocument(taskId, currentUserId, currentWorkspaceId, admin);
        return List.of();
    }

    /**
     * 卡片详情页反向查源文档切片：ES → Milvus 迁移期间返回空列表。
     */
    public List<RelatedChunkVO> findRelatedChunksForCard(KnowledgeCard card) {
        if (card == null || card.getId() == null
                || !KnowledgeCard.SOURCE_KNOWLEDGE.equals(card.getSourceType())
                || card.getSourceId() == null || card.getSourceId().isBlank()) {
            return List.of();
        }
        // TODO: ES → Milvus 迁移后，通过 Milvus ANN 恢复切片↔卡片关联
        log.debug("[ChunkCluster] ES 已迁移至 Milvus，切片关联暂不可用 cardId={}", card.getId());
        return List.of();
    }

    /**
     * 清除指定文档的聚类缓存（仅 Redis 操作，不依赖 ES）。
     */
    public void evictClusterCache(String taskId) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null && taskId != null && !taskId.isBlank()) {
            redis.delete(CACHE_PREFIX + taskId.trim());
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
                && currentWorkspaceId != null
                && Objects.equals(currentWorkspaceId, row.getWorkspaceId());
        if (!admin && !owner && !workspaceVisible) {
            throw new ResponseStatusException(FORBIDDEN, "无权查看该文档切片");
        }
        if (!DocumentMetadata.STATUS_SUCCESS.equals(row.getStatus())) {
            throw new ResponseStatusException(BAD_REQUEST, "文档尚未解析完成");
        }
        return row;
    }
}
