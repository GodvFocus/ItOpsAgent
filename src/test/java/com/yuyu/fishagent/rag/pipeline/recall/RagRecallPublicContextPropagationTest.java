package com.yuyu.fishagent.rag.pipeline.recall;

import com.yuyu.fishagent.auth.context.UserContext;
import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.card.mapper.CardRelationMapper;
import com.yuyu.fishagent.card.mapper.KnowledgeCardMapper;
import com.yuyu.fishagent.common.metrics.ChatMetrics;
import com.yuyu.fishagent.common.resilience.CircuitBreakerHelper;
import com.yuyu.fishagent.rag.config.KnowledgeProperties;
import com.yuyu.fishagent.rag.config.RagProperties;
import com.yuyu.fishagent.rag.pipeline.expand.RagHydeService;
import com.yuyu.fishagent.rag.pipeline.query.RagQueryRewrite;
import com.yuyu.fishagent.rag.pipeline.rerank.RagReranker;
import com.yuyu.fishagent.rag.tracing.RagQualityLogger;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class RagRecallPublicContextPropagationTest {

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void buildAugmentationCarriesWorkspaceContextIntoPublicRecallThreads() {
        RagProperties ragProperties = new RagProperties();
        ragProperties.setEnabled(true);
        ragProperties.getFusion().setEnabled(false);
        ragProperties.getRerank().setTopN(4);
        ragProperties.getRender().setMaxInjectedFacts(4);

        KnowledgeProperties knowledgeProperties = new KnowledgeProperties();
        ChatMetrics chatMetrics = new ChatMetrics(new SimpleMeterRegistry());
        CircuitBreakerHelper circuitBreakerHelper = new CircuitBreakerHelper(CircuitBreakerRegistry.ofDefaults());

        RagReranker reranker = Mockito.mock(RagReranker.class);
        when(reranker.rerank(anyString(), anyList(), anyInt()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<RagRecall.RecallHit> candidates = invocation.getArgument(1, List.class);
                    int topN = invocation.getArgument(2, Integer.class);
                    return candidates.stream().limit(topN).toList();
                });

        RagQualityLogger qualityLogger = Mockito.mock(RagQualityLogger.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = Mockito.mock(ObjectProvider.class);
        RagHydeService hydeService = new RagHydeService(chatModelProvider, ragProperties);

        RagRecall.DocumentSearcher emptySearcher = new RagRecall.DocumentSearcher() {
            @Override
            public List<RagRecall.RecallHit> searchByText(String sessionId, String subQueryText, int size) {
                return List.of();
            }

            @Override
            public List<RagRecall.RecallHit> searchByVector(String sessionId, String textToEmbed, int size) {
                return List.of();
            }
        };

        RagRecall.DocumentSearcher publicSearcher = new RagRecall.DocumentSearcher() {
            @Override
            public List<RagRecall.RecallHit> searchByText(String sessionId, String subQueryText, int size) {
                String workspaceId = UserContextHolder.currentWorkspaceIdOrNull();
                if (!"workspace-a".equals(workspaceId)) {
                    return List.of();
                }
                return List.of(new RagRecall.RecallHit(
                        "public-hit",
                        "公共区文档命中",
                        0.95,
                        RagRecall.RecallSource.TEXT,
                        "公共知识",
                        1.0,
                        System.currentTimeMillis(),
                        null,
                        null,
                        null
                ));
            }

            @Override
            public List<RagRecall.RecallHit> searchByVector(String sessionId, String textToEmbed, int size) {
                return List.of();
            }
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RagRecall.DefaultAugmentation augmentation = new RagRecall.DefaultAugmentation(
                    ragProperties,
                    knowledgeProperties,
                    new RagQueryRewrite.IdentityRewriter(),
                    rewrittenQuery -> List.of(rewrittenQuery),
                    emptySearcher,
                    emptySearcher,
                    null,
                    publicSearcher,
                    executor,
                    reranker,
                    hydeService,
                    qualityLogger,
                    circuitBreakerHelper,
                    chatMetrics,
                    Mockito.mock(CardRelationMapper.class),
                    Mockito.mock(KnowledgeCardMapper.class)
            );

            UserContextHolder.set(new UserContext(1L, "workspace-a", "alice", "Alice", "USER"));

            String block = augmentation.buildAugmentation("session-1", "公共知识问题")
                    .orElse("");

            assertThat(block).contains("公共区文档命中");
        } finally {
            executor.shutdownNow();
        }
    }
}
