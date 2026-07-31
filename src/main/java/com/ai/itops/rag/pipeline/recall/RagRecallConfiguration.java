package com.ai.itops.rag.pipeline.recall;

import com.ai.itops.rag.pipeline.expand.RagQueryExpand;
import com.ai.itops.rag.pipeline.expand.RagHydeService;
import com.ai.itops.rag.pipeline.query.RagQueryRewrite;
import com.ai.itops.common.metrics.ChatMetrics;
import com.ai.itops.common.resilience.CircuitBreakerHelper;
import com.ai.itops.common.trace.PromptTraceSupport;
import com.ai.itops.rag.config.KnowledgeProperties;
import com.ai.itops.rag.config.RagProperties;
import com.ai.itops.card.mapper.CardRelationMapper;
import com.ai.itops.card.mapper.KnowledgeCardMapper;
import com.ai.itops.rag.pipeline.rerank.DashScopeRagReranker;
import com.ai.itops.rag.pipeline.rerank.RagReranker;
import com.ai.itops.rag.tracing.RagQualityLogger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 召回与编排装配：虚拟线程池、三路 Milvus {@link RagRecall.DocumentSearcher}（用户记忆 / 用户知识库 / 公共知识）、
 * 对 Chat 暴露的 {@link RagRecall.Augmentation}。
 * <p>知识卡片搜索器（原 ES UserKnowledgeCardSearcher）已移除，后续通过 MySQL LIKE + Milvus 混合检索替代。</p>
 */
@Configuration
public class RagRecallConfiguration {

    @Bean(name = "ragRecallExecutor", destroyMethod = "shutdown")
    public ExecutorService ragRecallExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public RagReranker ragReranker(RagProperties ragProperties, CircuitBreakerHelper circuitBreakerHelper) {
        return new DashScopeRagReranker(ragProperties, circuitBreakerHelper);
    }

    @Bean
    public RagHydeService ragHydeService(
            @Qualifier("memoryChatModel") ObjectProvider<ChatModel> memoryChatModelProvider,
            RagProperties ragProperties,
            PromptTraceSupport promptTraceSupport,
            CircuitBreakerHelper circuitBreakerHelper) {
        return new RagHydeService(memoryChatModelProvider, ragProperties, promptTraceSupport, circuitBreakerHelper);
    }

    @Bean
    public RagRecall.Augmentation longTermRagContextService(
            RagProperties ragProperties,
            KnowledgeProperties knowledgeProperties,
            RagQueryRewrite.QueryRewriter queryRewriter,
            RagQueryExpand.SubQueryExpander subQueryExpander,
            UserMemoryMilvusSearcher userMemoryMilvusSearcher,
            UserKnowledgeMilvusSearcher userKnowledgeMilvusSearcher,
            PublicKnowledgeMilvusSearcher publicKnowledgeMilvusSearcher,
            @Qualifier("ragRecallExecutor") ExecutorService ragRecallExecutor,
            RagReranker ragReranker,
            RagHydeService ragHydeService,
            RagQualityLogger ragQualityLogger,
            CircuitBreakerHelper circuitBreakerHelper,
            ChatMetrics chatMetrics,
            CardRelationMapper cardRelationMapper,
            KnowledgeCardMapper knowledgeCardMapper) {
        return new RagRecall.DefaultAugmentation(
                ragProperties,
                knowledgeProperties,
                queryRewriter,
                subQueryExpander,
                userMemoryMilvusSearcher,
                userKnowledgeMilvusSearcher,
                null, // 知识卡片搜索器：ES 已迁移至 Milvus，暂用 null（后续通过 MySQL LIKE + Milvus 混合检索替代）
                publicKnowledgeMilvusSearcher,
                ragRecallExecutor,
                ragReranker,
                ragHydeService,
                ragQualityLogger,
                circuitBreakerHelper,
                chatMetrics,
                cardRelationMapper,
                knowledgeCardMapper);
    }
}
