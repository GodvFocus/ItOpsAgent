package com.ai.itops.chat;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.itops.agent.ChatAgent;
import com.ai.itops.agent.config.AgentProperties;
import com.ai.itops.chat.answer.EvidenceAssembler;
import com.ai.itops.chat.router.QueryRouter;
import com.ai.itops.chat.router.RouteDecision;
import com.ai.itops.chat.history.ChatMemoryStore;
import com.ai.itops.common.metrics.ChatMetrics;
import com.ai.itops.common.ratelimit.RateLimitService;
import com.ai.itops.common.trace.PromptTraceSupport;
import com.ai.itops.common.trace.TraceCollector;
import com.ai.itops.common.trace.TraceProperties;
import com.ai.itops.llm.config.ActiveChatModelContext;
import com.ai.itops.llm.config.FishLlmProperties;
import com.ai.itops.memory.LongTermMemoryIngestionService;
import com.ai.itops.memory.MemoryCompressionService;
import com.ai.itops.memory.agentstate.AgentStateStore;
import com.ai.itops.memory.agentstate.AgentStateUpdater;
import com.ai.itops.memory.config.MemoryProperties;
import com.ai.itops.memory.shortterm.ShortTermMemoryService;
import com.ai.itops.memory.shortterm.ShortTermMemorySnapshot;
import com.ai.itops.rag.pipeline.recall.RagRecall;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatServiceObservabilityTest {

    @Test
    void shouldFinishChatTurnMetricWhenEmitterTimesOutAndCancelsFlux() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicReference<Runnable> timeoutCallback = new AtomicReference<>();

        ChatAgent chatAgent = mock(ChatAgent.class);
        @SuppressWarnings("unchecked")
        Flux<NodeOutput> never = Flux.never();
        when(chatAgent.stream(any(), anyString(), anyString())).thenReturn(never);

        ShortTermMemoryService shortTermMemoryService = mock(ShortTermMemoryService.class);
        when(shortTermMemoryService.loadForTurnWithMetadata(any(), anyString(), any()))
                .thenReturn(new ShortTermMemoryService.ShortTermMemoryLoadResult(
                        new ShortTermMemorySnapshot("", List.of()), false));

        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryAcquireSessionLock(any(), anyString()))
                .thenReturn(RateLimitService.SessionLockHandle.managed("sid", "token"));
        when(rateLimitService.sessionLockWatchdogInterval()).thenReturn(Duration.ofSeconds(30));
        when(rateLimitService.refreshSessionLock(any(), any())).thenReturn(true);

        RagRecall.Augmentation augmentation = mock(RagRecall.Augmentation.class);
        when(augmentation.buildAugmentation(anyString(), anyString(), any(), anyInt())).thenReturn(Optional.empty());

        ActiveChatModelContext activeChatModelContext = mock(ActiveChatModelContext.class);
        when(activeChatModelContext.activeModelName()).thenReturn("deepseek-v4-flash");
        when(activeChatModelContext.effectiveContextWindow()).thenReturn(32_768);
        ChatModel fastRagChatModel = mock(ChatModel.class);
        QueryRouter queryRouter = input -> RouteDecision.troubleshootingAgent("test");
        TraceCollector traceCollector = new TraceCollector(new TraceProperties());

        ChatService service = new ChatService(
                chatAgent,
                fastRagChatModel,
                queryRouter,
                mock(ChatMemoryStore.class),
                shortTermMemoryService,
                mock(MemoryCompressionService.class),
                mock(LongTermMemoryIngestionService.class),
                augmentation,
                new AgentProperties(),
                new MemoryProperties(),
                rateLimitService,
                mock(ChatMetadataService.class),
                mock(AgentStateStore.class),
                mock(AgentStateUpdater.class),
                new ChatMetrics(registry),
                new ObjectMapper(),
                new FishLlmProperties(),
                activeChatModelContext,
                traceCollector,
                mock(com.ai.itops.common.trace.TraceFileWriter.class),
                mock(com.ai.itops.agent.tool.result.ToolResultGovernor.class),
                mock(EvidenceAssembler.class),
                CircuitBreakerRegistry.ofDefaults(),
                new PromptTraceSupport(traceCollector));

        SseEmitter emitter = mock(SseEmitter.class);
        doAnswer(invocation -> {
            timeoutCallback.set(invocation.getArgument(0));
            return null;
        }).when(emitter).onTimeout(any(Runnable.class));

        service.streamChat("sid", "hello", emitter);
        timeoutCallback.get().run();

        assertThat(registry.find("fish.chat.turn.duration")
                .tag("outcome", "error")
                .timer()
                .count()).isEqualTo(1);
    }
}
