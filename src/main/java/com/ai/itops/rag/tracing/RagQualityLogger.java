package com.ai.itops.rag.tracing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.itops.common.trace.MdcAsync;
import com.ai.itops.rag.config.RagProperties;
import com.ai.itops.rag.pipeline.recall.RagRecall;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * RAG 检索质量日志写入器。
 *
 * <p>采样、异步写入和失败降级都收敛在这里，召回编排只负责上报指标对象。
 * 原为 ES 写入，在 ES → Milvus 迁移中改为文件存储。
 *
 * TODO: 后续改为通过 MyBatis-Plus Mapper 写入 MySQL 表 itops_rag_trace，
 *       实现结构化的 RAG 质量追踪查询与报表。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagQualityLogger {

    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void initDir() {
        RagProperties.Tracing cfg = ragProperties.getTracing();
        if (!cfg.isEnabled()) {
            return;
        }
        try {
            Files.createDirectories(Paths.get(cfg.getStorageDir()));
        } catch (IOException e) {
            log.warn("[RagQualityLogger] 创建存储目录失败: {}", e.getMessage());
        }
    }

    /** 把本轮注入上下文的命中映射为 per-fact 明细（id / 来源标签 / 分数）。 */
    public static List<RagTraceDocument.InjectedFact> toInjectedFacts(List<RagRecall.RecallHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<RagTraceDocument.InjectedFact> out = new ArrayList<>(hits.size());
        for (RagRecall.RecallHit hit : hits) {
            RagTraceDocument.InjectedFact fact = new RagTraceDocument.InjectedFact();
            fact.setId(hit.id());
            fact.setSourceLabel(hit.effectiveSourceLabel());
            fact.setScore(hit.score());
            out.add(fact);
        }
        return out;
    }

    public void log(RagTraceDocument trace) {
        RagProperties.Tracing cfg = ragProperties.getTracing();
        if (!cfg.isEnabled() || trace == null) {
            return;
        }
        double sampleRate = Math.max(0.0, Math.min(1.0, cfg.getSampleRate()));
        if (sampleRate < 1.0 && ThreadLocalRandom.current().nextDouble() >= sampleRate) {
            return;
        }
        if (cfg.isAsync()) {
            MdcAsync.mdcRunAsync(() -> doSave(cfg.getStorageDir(), trace));
        } else {
            doSave(cfg.getStorageDir(), trace);
        }
    }

    private void doSave(String storageDir, RagTraceDocument trace) {
        try {
            Path file = Paths.get(storageDir, trace.getTraceId() + ".json");
            objectMapper.writeValue(file.toFile(), trace);
        } catch (Exception e) {
            log.warn("[RagQualityLogger] 写入 RAG 追踪日志失败 traceId={}: {}", trace.getTraceId(), e.getMessage());
        }
    }
}
