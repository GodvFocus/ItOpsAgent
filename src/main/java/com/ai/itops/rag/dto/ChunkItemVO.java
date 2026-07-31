package com.ai.itops.rag.dto;

/**
 * 切片列表项。
 */
public record ChunkItemVO(
        Integer chunkIndex,
        String content,
        Integer charCount,
        Integer relatedCardCount
) {
}
