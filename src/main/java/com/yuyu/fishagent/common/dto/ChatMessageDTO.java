package com.yuyu.fishagent.common.dto;

import com.yuyu.fishagent.chat.dto.StructuredAnswer;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话消息持久化 DTO（与前端展示模型一致）。
 * <p>{@code role} 取值：{@code user} / {@code assistant} / {@code system} / {@code tool}。
 */
@Data
@NoArgsConstructor
public class ChatMessageDTO {

    private String role;

    private String content;

    private long createdAt;

    /**
     * 助手消息的结构化回答。
     * 用户/系统/工具消息通常为空；保留在 DTO 上是为了让历史回放与实时 SSE 使用同一展示模型。
     */
    private StructuredAnswer structuredAnswer;

    public ChatMessageDTO(String role, String content, long createdAt) {
        this(role, content, createdAt, null);
    }

    public ChatMessageDTO(String role, String content, long createdAt, StructuredAnswer structuredAnswer) {
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
        this.structuredAnswer = structuredAnswer;
    }

    public static ChatMessageDTO of(String role, String content) {
        return new ChatMessageDTO(role, content, System.currentTimeMillis());
    }

    public static ChatMessageDTO assistant(String content, StructuredAnswer structuredAnswer) {
        return new ChatMessageDTO("assistant", content, System.currentTimeMillis(), structuredAnswer);
    }
}
