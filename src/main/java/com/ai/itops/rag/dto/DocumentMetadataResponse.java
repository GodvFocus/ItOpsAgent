package com.ai.itops.rag.dto;

/**
 * 知识库上传任务列表项（供管理页展示）。
 */
public record DocumentMetadataResponse(
        String taskId,
        String fileName,
        long fileSize,
        Long ownerUserId,
        String workspaceId,
        String visibility,
        String status,
        Integer chunkCount,
        String errorMsg,
        String createdAt,
        String updatedAt
) {
}
