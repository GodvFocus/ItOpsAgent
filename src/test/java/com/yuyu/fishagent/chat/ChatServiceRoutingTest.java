package com.yuyu.fishagent.chat;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.agent.ChatAgent;
import com.yuyu.fishagent.agent.config.AgentProperties;
import com.yuyu.fishagent.agent.tool.result.ToolResultGovernor;
import com.yuyu.fishagent.chat.answer.EvidenceAssembler;
import com.yuyu.fishagent.chat.history.ChatMemoryStore;
import com.yuyu.fishagent.chat.router.QueryRouter;
import com.yuyu.fishagent.chat.router.RouteDecision;
import com.yuyu.fishagent.common.metrics.ChatMetrics;
import com.yuyu.fishagent.common.ratelimit.RateLimitService;
import com.yuyu.fishagent.common.trace.TraceCollector;
import com.yuyu.fishagent.common.trace.TraceFileWriter;
import com.yuyu.fishagent.common.trace.TraceProperties;
import com.yuyu.fishagent.common.trace.TurnTrace;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceRoutingTest {

    @Test
    void fastRagQuestionShouldBypassAgentAndPersistFastRouteTrace() {
        ChatAgent chatAgent = mock(ChatAgent.class);
        ChatModel fastRagChatModel = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(response.getResult().getOutput().getText()).thenReturn("按 Runbook 先灰度重启再做健康检查。");
        when(fastRagChatModel.stream(any(Prompt.class))).thenReturn(Flux.just(response));

        TraceFileWriter traceFileWriter = mock(TraceFileWriter.class);
        ChatService service = newService(
                chatAgent,
                fastRagChatModel,
                input -> RouteDecision.fastRag("matched sop"),
                traceFileWriter);

        service.streamChat("sid", "order-service 重启 SOP 是什么？", mock(SseEmitter.class));

        verify(chatAgent, never()).stream(any(), anyString(), anyString());
        verify(fastRagChatModel).stream(any(Prompt.class));
        ArgumentCaptor<TurnTrace> captor = ArgumentCaptor.forClass(TurnTrace.class);
        verify(traceFileWriter).persistAsync(captor.capture());
        assertThat(captor.getValue().getRoute()).isEqualTo("FAST_RAG");
    }

    @Test
    void troubleshootingQuestionShouldEnterAgentRoute() {
        ChatAgent chatAgent = mock(ChatAgent.class);
        ChatModel fastRagChatModel = mock(ChatModel.class);
        @SuppressWarnings("unchecked")
        Flux<NodeOutput> agentFlux = Flux.just(new StreamingOutput<>("先查日志", "agent", null));
        when(chatAgent.stream(any(), anyString(), anyString())).thenReturn(agentFlux);

        TraceFileWriter traceFileWriter = mock(TraceFileWriter.class);
        ChatService service = newService(
                chatAgent,
                fastRagChatModel,
                input -> RouteDecision.troubleshootingAgent("matched log + error"),
                traceFileWriter);

        service.streamChat("sid", "order-service /api/v1/order/create 报 ECONNRESET，帮我结合日志排查", mock(SseEmitter.class));

        verify(chatAgent).stream(any(), anyString(), anyString());
        verify(fastRagChatModel, never()).stream(any(Prompt.class));
        ArgumentCaptor<TurnTrace> captor = ArgumentCaptor.forClass(TurnTrace.class);
        verify(traceFileWriter).persistAsync(captor.capture());
        assertThat(captor.getValue().getRoute()).isEqualTo("TROUBLESHOOTING_AGENT");
    }

    private ChatService newService(ChatAgent chatAgent,
                                   ChatModel fastRagChatModel,
                                   QueryRouter queryRouter,
                                   TraceFileWriter traceFileWriter) {
        ShortTermMemoryService shortTermMemoryService = mock(ShortTermMemoryService.class);
        when(shortTermMemoryService.loadForTurnWithMetadata(any(), anyString(), any()))
                .thenReturn(new ShortTermMemoryService.ShortTermMemoryLoadResult(
                        new ShortTermMemorySnapshot("", List.of()), false));
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryAcquireSessionLock(any(), anyString())).thenReturn(true);
        RagRecall.Augmentation augmentation = mock(RagRecall.Augmentation.class);
        when(augmentation.buildAugmentationWithSources(anyString(), anyString(), any(), anyInt()))
                .thenReturn(Optional.empty());
        ActiveChatModelContext activeChatModelContext = mock(ActiveChatModelContext.class);
        when(activeChatModelContext.activeModelName()).thenReturn("deepseek-v4-flash");
        when(activeChatModelContext.effectiveContextWindow()).thenReturn(32_768);

        return new ChatService(
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
                new ChatMetrics(new SimpleMeterRegistry()),
                new ObjectMapper(),
                new FishLlmProperties(),
                activeChatModelContext,
                new TraceCollector(new TraceProperties()),
                traceFileWriter,
                mock(ToolResultGovernor.class),
                mock(EvidenceAssembler.class));
    }
}
