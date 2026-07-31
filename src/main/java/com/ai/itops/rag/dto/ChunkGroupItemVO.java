package com.ai.itops.rag.dto;

/**
 * 切片主题分组摘要。
 */
public record ChunkGroupItemVO(
        Integer groupIndex,
        String title,
        Integer chunkCount
) {
}
