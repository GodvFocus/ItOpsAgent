package com.ai.itops.rag.pipeline.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.itops.common.resilience.CircuitBreakerHelper;
import com.ai.itops.common.trace.PromptTraceSupport;
import com.ai.itops.rag.config.RagProperties;
import com.ai.itops.rag.config.RagProperties.RewriteProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 查询重写装配（第一类）：仅注册 {@link RagQueryRewrite.QueryRewriter}。
 */
@Slf4j
@Configuration
public class RagQueryRewriteConfiguration {

    @Bean
    public RagQueryRewrite.QueryRewriter queryRewriter(
            RagProperties ragProperties,
            ObjectProvider<ChatModel> chatModelProvider,
            ObjectMapper objectMapper,
            PromptTraceSupport promptTraceSupport,
            CircuitBreakerHelper circuitBreakerHelper) {
        if (ragProperties.getRewriteProvider() == RewriteProvider.CHAT_MODEL) {
            ChatModel model = chatModelProvider.getIfAvailable();
            if (model != null) {
                return new RagQueryRewrite.ChatModelRewriter(
                        model, ragProperties, objectMapper, promptTraceSupport, circuitBreakerHelper);
            }
            log.warn("[RagQueryRewriteConfiguration] rewrite-provider=CHAT_MODEL 但 ChatModel 不可用，回退 IdentityRewriter");
        }
        return new RagQueryRewrite.IdentityRewriter();
    }
}
