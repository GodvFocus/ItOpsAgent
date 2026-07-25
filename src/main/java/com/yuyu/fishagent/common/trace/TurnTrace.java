package com.yuyu.fishagent.common.trace;

import lombok.Data;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单轮对话执行 trace。
 *
 * <p>只存节点片段与指标，不保存完整 prompt/answer，兼顾排障价值和存储边界。
 * 序列化时通过 Jackson 写入 JSON 文件，{@code completed} 和 {@code nextNodeOrder}
 * 标记为 transient 不参与序列化。</p>
 */
@Data
public class TurnTrace {

    private String turnId;

    private String sessionId;

    private String traceId;

    private long startTimeMs;

    private long totalLatencyMs;

    private String status = "RUNNING";

    private String route;

    private String routeReason;

    private String ragInjected;

    private String memoryInjected;

    /**
     * NodeOutput 可能由 Reactor 不同线程回调，列表必须允许并发追加。
     *
     * <p>trace 节点通常只在单轮对话内追加、结束后整体读取；CopyOnWriteArrayList
     * 牺牲少量写入成本，换取迭代/序列化时的稳定快照，避免 ArrayList 并发扩容损坏。</p>
     */
    private List<Node> nodes = new CopyOnWriteArrayList<>();

    private transient AtomicBoolean completed = new AtomicBoolean(false);

    private transient AtomicInteger nextNodeOrder = new AtomicInteger(1);

    @Data
    public static class Node {
        private int order;
        private String type;
        private String nodeName;
        private String contentSnippet;
        private long latencyMs;
        private String status;
        /** 工具结果治理处置方式：truncated / summarized / retrieved；普通节点为空。 */
        private String disposition;
    }
}
