package com.ai.itops.llm.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 按 {@link FishLlmEmbeddingProperties#getProvider()} 选出主嵌入实现并标为 {@code @Primary}，
 * 供长期记忆写入、RAG 向量腿等统一注入；与对话路由 {@link FishLlmProperties#getChatProvider()} 独立。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class FishEmbeddingModelConfiguration {

    private final FishLlmEmbeddingProperties embeddingProperties;

    @Bean
    @Primary
    public EmbeddingModel fishPrimaryEmbeddingModel(
            ObjectProvider<OllamaEmbeddingModel> ollamaEmbeddingModel) {
        return switch (embeddingProperties.getProvider()) {
            case OLLAMA -> requireBean(
                    ollamaEmbeddingModel.getIfAvailable(),
                    "OLLAMA",
                    "OllamaEmbeddingModel");
            case DEEPSEEK -> throw new IllegalStateException(
                    "fish.llm.embedding.provider=DEEPSEEK 暂不支持；嵌入请使用 OLLAMA");
        };
    }

    private EmbeddingModel requireBean(
            EmbeddingModel impl,
            String providerLabel,
            String beanSimpleName) {
        if (impl != null) {
            return impl;
        }
        String msg = String.format(
                "fish.llm.embedding.provider=%s 需要已注册的 %s Bean，但未找到。"
                        + " 请确认对应 starter 与本地/云端依赖可用。",
                providerLabel, beanSimpleName);
        log.error("[FishLlm] {}", msg);
        throw new IllegalStateException(msg);
    }
}
