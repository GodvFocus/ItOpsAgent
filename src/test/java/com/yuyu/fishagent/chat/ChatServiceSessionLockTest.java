package com.yuyu.fishagent.chat;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.agent.ChatAgent;
import com.yuyu.fishagent.agent.config.AgentProperties;
import com.yuyu.fishagent.agent.tool.result.ToolResultGovernor;
import com.yuyu.fishagent.chat.answer.EvidenceAssembler;
import com.yuyu.fishagent.chat.history.ChatMemoryStore;
import com.yuyu.fishagent.chat.router.QueryRouter;
import com.yuyu.fishagent.chat.router.RouteDecision;
import com.yuyu.fishagent.common.exception.SessionLockedException;
import com.yuyu.fishagent.common.metrics.ChatMetrics;
import com.yuyu.fishagent.common.ratelimit.RateLimitService;
import com.yuyu.fishagent.common.trace.PromptTraceSupport;
import com.yuyu.fishagent.common.trace.TraceCollector;
import com.yuyu.fishagent.common.trace.TraceFileWriter;
import com.yuyu.fishagent.common.trace.TraceProperties;
import com.yuyu.fishagent.llm.config.ActiveChatModelContext;
import com.yuyu.fishagent.llm.config.FishLlmProperties;
import com.yuyu.fishagent.memory.LongTermMemoryIngestionService;
import com.yuyu.fishagent.memory.MemoryCompressionService;
import com.yuyu.fishagent.memory.agentstate.AgentStateStore;
import com.yuyu.fishagent.memory.agentstate.AgentStateUpdater;
import com.yuyu.fishagent.memory.config.MemoryProperties;
import com.yuyu.fishagent.memory.shortterm.ShortTermMemoryService;
import com.yuyu.fishagent.memory.shortterm.ShortTermMemorySnapshot;
import com.yuyu.fishagent.rag.pipeline.recall.RagRecall;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceSessionLockTest {

    @Test
    void shouldReleaseOwnedLockWhenClientDisconnects() {
        ChatAgent chatAgent = mock(ChatAgent.class);
        @SuppressWarnings("unchecked")
        Flux<NodeOutput> never = Flux.never();
        when(chatAgent.stream(any(), anyString(), anyString())).thenReturn(never);

        RateLimitService rateLimitService = mock(RateLimitService.class);
        RateLimitService.SessionLockHandle handle = RateLimitService.SessionLockHandle.managed("sid", "token");
        when(rateLimitService.tryAcquireSessionLock(any(), anyString())).thenReturn(handle);
        when(rateLimitService.sessionLockWatchdogInterval()).thenReturn(Duration.ofSeconds(30));
        when(rateLimitService.refreshSessionLock(any(), same(handle))).thenReturn(true);

        ChatService service = newService(chatAgent, rateLimitService);
        AtomicReference<Runnable> completionCallback = new AtomicReference<>();
        SseEmitter emitter = mock(SseEmitter.class);
        doAnswer(invocation -> {
            completionCallback.set(invocation.getArgument(0));
            return null;
        }).when(emitter).onCompletion(any(Runnable.class));

        service.streamChat("sid", "hello", emitter);
        completionCallback.get().run();

        verify(rateLimitService).releaseSessionLock(null, handle);
    }

    @Test
    void shouldAbortStreamWhenWatchdogLosesOwnership() {
        ChatAgent chatAgent = mock(ChatAgent.class);
        @SuppressWarnings("unchecked")
        Flux<NodeOutput> never = Flux.never();
        when(chatAgent.stream(any(), anyString(), anyString())).thenReturn(never);

        RateLimitService rateLimitService = mock(RateLimitService.class);
        RateLimitService.SessionLockHandle handle = RateLimitService.SessionLockHandle.managed("sid", "token");
        when(rateLimitService.tryAcquireSessionLock(any(), anyString())).thenReturn(handle);
        when(rateLimitService.sessionLockWatchdogInterval()).thenReturn(Duration.ofMillis(10));
        when(rateLimitService.refreshSessionLock(any(), same(handle))).thenReturn(false);

        ChatService service = newService(chatAgent, rateLimitService);
        SseEmitter emitter = mock(SseEmitter.class);

        service.streamChat("sid", "hello", emitter);

        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(emitter, timeout(1000)).completeWithError(errorCaptor.capture());
        assertThat(errorCaptor.getValue()).isInstanceOf(SessionLockedException.class);
        verify(rateLimitService, timeout(1000)).releaseSessionLock(null, handle);
    }

    private ChatService newService(ChatAgent chatAgent, RateLimitService rateLimitService) {
        ShortTermMemoryService shortTermMemoryService = mock(ShortTermMemoryService.class);
        when(shortTermMemoryService.loadForTurnWithMetadata(any(), anyString(), any()))
                .thenReturn(new ShortTermMemoryService.ShortTermMemoryLoadResult(
                        new ShortTermMemorySnapshot("", List.of()), false));

        RagRecall.Augmentation augmentation = mock(RagRecall.Augmentation.class);
        when(augmentation.buildAugmentationWithSources(anyString(), anyString(), any(), anyInt()))
                .thenReturn(Optional.empty());

        ActiveChatModelContext activeChatModelContext = mock(ActiveChatModelContext.class);
        when(activeChatModelContext.activeModelName()).thenReturn("deepseek-v4-flash");
        when(activeChatModelContext.effectiveContextWindow()).thenReturn(32_768);

        TraceCollector traceCollector = new TraceCollector(new TraceProperties());

        return new ChatService(
                chatAgent,
                mock(ChatModel.class),
                troubleshootingRouter(),
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
                new ChatMetrics(new SimpleMeterRegistry()),
                new ObjectMapper(),
                new FishLlmProperties(),
                activeChatModelContext,
                traceCollector,
                mock(TraceFileWriter.class),
                mock(ToolResultGovernor.class),
                mock(EvidenceAssembler.class),
                CircuitBreakerRegistry.ofDefaults(),
                new PromptTraceSupport(traceCollector));
    }

    private QueryRouter troubleshootingRouter() {
        return input -> RouteDecision.troubleshootingAgent("test");
    }
}
