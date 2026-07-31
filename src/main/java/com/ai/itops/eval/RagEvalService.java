package com.ai.itops.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.itops.rag.config.RagProperties;
import com.ai.itops.rag.pipeline.recall.RagRecall;
import com.ai.itops.rag.pipeline.rerank.RagReranker;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 真实 RAG 检索评测服务。
 *
 * <p>每次调用都重新读取 golden set，便于在不重启应用的情况下替换外部评测文件；
 * 检索器仍然使用当前请求线程中的用户和 workspace 上下文。</p>
 */
@Service
@RequiredArgsConstructor
public class RagEvalService {

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final RagEvalProperties evalProperties;
    private final RagProperties ragProperties;
    private final List<RagRecall.DocumentSearcher> searchers;
    private final RagReranker reranker;

    public HybridEvalReport run(Integer k, Integer perLegK) {
        List<GoldenSet.Case> cases = loadGoldenSet();
        int topK = positiveOrDefault(k, evalProperties.getK());
        int legK = positiveOrDefault(perLegK, evalProperties.getPerLegK());
        return new EvalRunner(ragProperties).runEndToEnd(cases, searchers, reranker, topK, legK);
    }

    private List<GoldenSet.Case> loadGoldenSet() {
        String path = evalProperties.getGoldenSetPath();
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("RAG golden set 路径未配置");
        }
        Resource resource = resourceLoader.getResource(path.trim());
        if (!resource.exists()) {
            throw new IllegalStateException("RAG golden set 不存在: " + path);
        }
        try (InputStream input = resource.getInputStream()) {
            List<GoldenSet.Case> cases = objectMapper.readValue(
                    input, new TypeReference<>() {
                    });
            if (cases == null || cases.isEmpty()) {
                throw new IllegalStateException("RAG golden set 为空: " + path);
            }
            return List.copyOf(cases);
        } catch (IOException e) {
            throw new IllegalStateException("RAG golden set 读取失败: " + path, e);
        }
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? Math.max(1, fallback) : value;
    }
}
