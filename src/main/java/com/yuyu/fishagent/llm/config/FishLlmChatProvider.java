package com.yuyu.fishagent.llm.config;

import java.util.Locale;

/**
 * 对话 / 嵌入业务侧提供方枚举（嵌入路由复用本枚举类型，见 {@link FishLlmEmbeddingProperties}）。
 * <p>
 * 与 Spring AI 自动配置中的 {@code spring.ai.model.chat} 取值对应关系：
 * {@link #OLLAMA} → {@code ollama}，{@link #DEEPSEEK} → {@code openai}
 * （DeepSeek 使用 OpenAI-compatible 客户端）。应用代码只依赖 {@link org.springframework.ai.chat.model.ChatModel}。
 * </p>
 */
public enum FishLlmChatProvider {

    OLLAMA("ollama"),

    DEEPSEEK("openai");

    private final String springAiModelChatValue;

    FishLlmChatProvider(String springAiModelChatValue) {
        this.springAiModelChatValue = springAiModelChatValue;
    }

    public String toSpringAiModelChatValue() {
        return springAiModelChatValue;
    }

    /**
     * 从配置文件或环境变量中的字符串解析枚举（大小写不敏感）。
     *
     * @param raw 原始字符串，可为 {@code null} 或空白
     * @return 解析成功返回对应枚举；为空时默认 {@link #OLLAMA}
     * @throws IllegalArgumentException 无法识别时抛出，避免启动后静默连错模型
     */
    public static FishLlmChatProvider parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return OLLAMA;
        }
        String n = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (n) {
            case "OLLAMA" -> OLLAMA;
            case "DEEPSEEK" -> DEEPSEEK;
            default -> throw new IllegalArgumentException(
                    "未知的 fish.llm.chat-provider / embedding.provider: '" + raw
                            + "'，请使用 OLLAMA 或 DEEPSEEK");
        };
    }
}
