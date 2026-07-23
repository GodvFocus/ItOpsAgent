package com.yuyu.fishagent.common.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ThreadLocalRandom;

/**
 * TurnTrace 异步文件写入器。
 *
 * <p>将每轮对话的 trace 以 JSON 文件形式写入本地磁盘，文件名格式为 {turnId}.json。
 * 写入失败只记录日志，不影响 SSE 主链路。采样和文档大小限制也收敛在这里。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraceFileWriter {

    private final TraceProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void initDir() {
        try {
            Files.createDirectories(Paths.get(properties.getStorageDir()));
        } catch (IOException e) {
            log.warn("[TraceFileWriter] 创建存储目录失败: {}", e.getMessage());
        }
    }

    public void persistAsync(TurnTrace trace) {
        if (!shouldPersist(trace)) return;
        MdcAsync.mdcRunAsync(() -> persist(trace));
    }

    private boolean shouldPersist(TurnTrace trace) {
        if (!properties.isEnabled() || trace == null) return false;
        double sampleRate = Math.max(0.0, Math.min(1.0, properties.getSampleRate()));
        return sampleRate >= 1.0 || ThreadLocalRandom.current().nextDouble() < sampleRate;
    }

    private void persist(TurnTrace trace) {
        try {
            enforceDocumentLimit(trace);
            Path file = Paths.get(properties.getStorageDir(),
                    trace.getTurnId() + ".json");
            objectMapper.writeValue(file.toFile(), trace);
        } catch (Exception e) {
            log.warn("[TraceFileWriter] 写入失败 turnId={}: {}", trace.getTurnId(), e.getMessage());
        }
    }

    /**
     * 落盘前按配置限制单条 trace 文档大小。
     *
     * <p>字段片段在采集时已做单字段截断；这里兜住极端流式场景下节点数过多导致的文档膨胀。
     * 为保留执行前半段排障价值，采用删尾策略并追加一个截断说明节点。</p>
     */
    void enforceDocumentLimit(TurnTrace trace) {
        int maxChars = Math.max(1_000, properties.getDocMaxChars());
        if (estimateChars(trace) <= maxChars) {
            return;
        }

        int removed = 0;
        while (estimateChars(trace) > maxChars && !trace.getNodes().isEmpty()) {
            trace.getNodes().remove(trace.getNodes().size() - 1);
            removed++;
        }
        if (removed > 0) {
            TurnTrace.Node marker = new TurnTrace.Node();
            marker.setOrder(trace.getNextNodeOrder().getAndIncrement());
            marker.setNodeName("trace-truncated");
            marker.setType("system");
            marker.setStatus("SUCCESS");
            marker.setContentSnippet("Trace document exceeded fish.trace.doc-max-chars; removed tail nodes=" + removed);
            trace.getNodes().add(marker);
            while (estimateChars(trace) > maxChars && !trace.getNodes().isEmpty()) {
                trace.getNodes().remove(trace.getNodes().size() - 1);
            }
        }
    }

    private int estimateChars(TurnTrace trace) {
        int total = 256;
        total += length(trace.getTurnId());
        total += length(trace.getSessionId());
        total += length(trace.getTraceId());
        total += length(trace.getStatus());
        total += length(trace.getRagInjected());
        total += length(trace.getMemoryInjected());
        for (TurnTrace.Node node : trace.getNodes()) {
            total += 64;
            total += length(node.getType());
            total += length(node.getNodeName());
            total += length(node.getStatus());
            total += length(node.getContentSnippet());
        }
        return total;
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }
}
