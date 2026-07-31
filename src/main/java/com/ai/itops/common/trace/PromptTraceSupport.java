package com.ai.itops.common.trace;

import com.ai.itops.common.util.TokenEstimator;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Prompt → TurnTrace 的统一桥接。
 *
 * <p>业务层只负责声明“这次模型调用属于哪个阶段”，具体如何提取 prompt 指纹、长度、
 * token 估算与分段统计，都收敛在这里，避免各处重复编码。</p>
 */
@Component
@RequiredArgsConstructor
public class PromptTraceSupport {

    private final TraceCollector traceCollector;

    public void recordCurrentTurnPrompt(String stage, Prompt prompt) {
        recordPrompt(TraceContext.currentTurnId(), stage, prompt, PromptTraceMetadata.empty());
    }

    public void recordCurrentTurnPrompt(String stage, Prompt prompt, PromptTraceMetadata metadata) {
        recordPrompt(TraceContext.currentTurnId(), stage, prompt, metadata);
    }

    public void recordPrompt(String turnId, String stage, Prompt prompt) {
        recordPrompt(turnId, stage, prompt, PromptTraceMetadata.empty());
    }

    public void recordPrompt(String turnId, String stage, Prompt prompt, PromptTraceMetadata metadata) {
        if (prompt == null) {
            return;
        }
        recordMessages(turnId, stage, prompt.getInstructions(), metadata);
    }

    public void recordCurrentTurnMessages(String stage, List<Message> messages) {
        recordMessages(TraceContext.currentTurnId(), stage, messages, PromptTraceMetadata.empty());
    }

    public void recordCurrentTurnMessages(String stage, List<Message> messages, PromptTraceMetadata metadata) {
        recordMessages(TraceContext.currentTurnId(), stage, messages, metadata);
    }

    public void recordMessages(String turnId, String stage, List<Message> messages) {
        recordMessages(turnId, stage, messages, PromptTraceMetadata.empty());
    }

    public void recordMessages(String turnId, String stage, List<Message> messages, PromptTraceMetadata metadata) {
        if (turnId == null || messages == null || messages.isEmpty()) {
            return;
        }
        traceCollector.recordPrompt(turnId, buildPromptCall(stage, messages, metadata == null ? PromptTraceMetadata.empty() : metadata));
    }

    private TurnTrace.PromptCall buildPromptCall(String stage, List<Message> messages, PromptTraceMetadata metadata) {
        RenderedPrompt rendered = renderMessages(messages);
        TurnTrace.PromptCall promptCall = new TurnTrace.PromptCall();
        promptCall.setStage(stage);
        promptCall.setFingerprint(sha256Hex(rendered.renderedPrompt()));
        promptCall.setTotalChars(rendered.totalChars());
        promptCall.setEstimatedTokens(TokenEstimator.estimate(rendered.renderedPrompt()));
        promptCall.setMessageCount(rendered.messageCount());
        promptCall.setSegmentLengths(rendered.segmentLengths());
        promptCall.setSystemChars(rendered.systemChars());
        promptCall.setUserChars(rendered.userChars());
        promptCall.setAssistantChars(rendered.assistantChars());
        promptCall.setToolChars(rendered.toolChars());
        promptCall.setOtherChars(rendered.otherChars());
        promptCall.setRagInjectionCount(metadata.ragInjectionCount());
        promptCall.setMemoryItemCount(metadata.memoryItemCount());
        promptCall.setCacheHit(metadata.cacheHit());
        promptCall.setCacheHitRate(metadata.cacheHitRate());
        promptCall.setStatus("SENT");
        return promptCall;
    }

    private RenderedPrompt renderMessages(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        List<Integer> segmentLengths = new ArrayList<>();
        int systemChars = 0;
        int userChars = 0;
        int assistantChars = 0;
        int toolChars = 0;
        int otherChars = 0;
        int messageCount = 0;
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            messageCount++;
            String role = message.getMessageType() == null
                    ? "unknown"
                    : message.getMessageType().name().toLowerCase(Locale.ROOT);
            String text = message.getText();
            int length = text == null ? 0 : text.length();
            segmentLengths.add(length);
            switch (role) {
                case "system" -> systemChars += length;
                case "user" -> userChars += length;
                case "assistant" -> assistantChars += length;
                case "tool" -> toolChars += length;
                default -> otherChars += length;
            }
            sb.append('[').append(role).append(']').append('\n');
            if (text != null && !text.isBlank()) {
                sb.append(text);
            }
            sb.append("\n\n");
        }
        String renderedPrompt = sb.toString().trim();
        return new RenderedPrompt(
                renderedPrompt,
                systemChars + userChars + assistantChars + toolChars + otherChars,
                messageCount,
                List.copyOf(segmentLengths),
                systemChars,
                userChars,
                assistantChars,
                toolChars,
                otherChars
        );
    }

    private String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public record PromptTraceMetadata(
            Integer ragInjectionCount,
            Integer memoryItemCount,
            Boolean cacheHit,
            Double cacheHitRate) {

        public static PromptTraceMetadata empty() {
            return new PromptTraceMetadata(0, 0, null, null);
        }
    }

    private record RenderedPrompt(
            String renderedPrompt,
            int totalChars,
            int messageCount,
            List<Integer> segmentLengths,
            int systemChars,
            int userChars,
            int assistantChars,
            int toolChars,
            int otherChars) {
    }
}
