package com.ai.itops.common.trace;

import com.ai.itops.common.metrics.ChatMetrics;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TraceCollectorTest {

    @Test
    void recordsSnippetsAndCompletesOnlyOnce() {
        TraceCollector collector = new TraceCollector(new TraceProperties());
        TurnTrace trace = collector.startTurn("turn-1", "sid", "trace-1");
        PromptTraceSupport promptTraceSupport = new PromptTraceSupport(collector);

        collector.recordRagInjected("turn-1", "R".repeat(500));
        collector.recordMemoryInjected("turn-1", "M".repeat(500));
        promptTraceSupport.recordMessages(
                "turn-1",
                "agent-main",
                java.util.List.of(new SystemMessage("你是助手"), new UserMessage("你好")),
                new PromptTraceSupport.PromptTraceMetadata(3, 5, null, null));
        collector.recordNode("turn-1", "llm", "thought", "A".repeat(500), 12, "SUCCESS");
        collector.finishTurn("turn-1", ChatMetrics.Outcome.SUCCESS);
        collector.finishTurn("turn-1", ChatMetrics.Outcome.ERROR);

        assertThat(trace.getRagInjected()).hasSize(200);
        assertThat(trace.getMemoryInjected()).hasSize(200);
        assertThat(trace.getPrompts()).singleElement().satisfies(prompt -> {
            assertThat(prompt.getFingerprint()).hasSize(64);
            assertThat(prompt.getTotalChars()).isEqualTo(6);
            assertThat(prompt.getEstimatedTokens()).isPositive();
            assertThat(prompt.getSegmentLengths()).containsExactly(4, 2);
            assertThat(prompt.getRagInjectionCount()).isEqualTo(3);
            assertThat(prompt.getMemoryItemCount()).isEqualTo(5);
        });
        assertThat(trace.getNodes()).hasSize(1);
        assertThat(trace.getNodes().get(0).getContentSnippet()).hasSize(200);
        assertThat(trace.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void recordsNodesSafelyFromConcurrentCallbacks() throws Exception {
        TraceCollector collector = new TraceCollector(new TraceProperties());
        TurnTrace trace = collector.startTurn("turn-1", "sid", "trace-1");
        ExecutorService pool = Executors.newFixedThreadPool(8);

        for (int i = 0; i < 100; i++) {
            int index = i;
            pool.submit(() -> collector.recordNode("turn-1", "node-" + index, "node", "content", 0, "SUCCESS"));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(trace.getNodes()).hasSize(100);
        assertThat(trace.getNodes())
                .extracting(TurnTrace.Node::getOrder)
                .doesNotHaveDuplicates()
                .contains(1, 100);
    }
}
