package com.ai.itops.security.permission;

import com.ai.itops.rag.entity.DocumentMetadata;
import com.ai.itops.rag.mapper.DocumentMetadataMapper;
import com.ai.itops.rag.pipeline.recall.RagRecall;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Milvus 结果的关系库二次校验，防止脏向量或旧索引绕过 Workspace 隔离。 */
@Service
@RequiredArgsConstructor
public class WorkspaceRecallSecurityService {

    private final PermissionEvaluator permissionEvaluator;
    private final DocumentMetadataMapper documentMetadataMapper;

    public List<RagRecall.RecallHit> filter(Long userId, String workspaceId,
                                            List<RagRecall.RecallHit> hits) {
        permissionEvaluator.checkWorkspacePermission(userId, workspaceId, WorkspacePermission.DOCUMENT_READ);
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        Set<String> taskIds = hits.stream()
                .map(RagRecall.RecallHit::docId)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        if (taskIds.isEmpty()) {
            return List.of();
        }
        Set<String> validTaskIds = new HashSet<>(documentMetadataMapper.selectList(
                        Wrappers.<DocumentMetadata>lambdaQuery()
                                .eq(DocumentMetadata::getWorkspaceId, workspaceId)
                                .in(DocumentMetadata::getTaskId, taskIds)
                                .ne(DocumentMetadata::getStatus, DocumentMetadata.STATUS_FAILED))
                .stream().map(DocumentMetadata::getTaskId).toList());
        return hits.stream().filter(hit -> validTaskIds.contains(hit.docId())).toList();
    }
}
