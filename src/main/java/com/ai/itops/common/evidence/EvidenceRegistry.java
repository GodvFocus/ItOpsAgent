package com.ai.itops.common.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ai.itops.rag.pipeline.recall.RagRecall;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按 turn 隔离的统一 Evidence Registry。
 *
 * <p>Registry 只负责登记和读取证据，不允许更新已登记对象。工具结果在返回模型前会被补上
 * Evidence ID，因此日志、状态快照、知识命中和工单记录都能走同一套引用校验。</p>
 */
@Slf4j
@Component
public class EvidenceRegistry {

    private static final String ANONYMOUS_TURN = "anonymous";

    private final ObjectMapper objectMapper;
    private final Map<String, TurnEvidence> turns = new ConcurrentHashMap<>();

    public EvidenceRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 开始一个 turn，并清理同名旧状态，避免旧请求的证据串入新请求。 */
    public void startTurn(String turnId) {
        if (validTurnId(turnId)) {
            turns.put(turnId, new TurnEvidence());
        }
    }

    /** 登记一条不可变证据并返回分配的 turn 内 ID。 */
    public Evidence register(String turnId,
                             EvidenceType type,
                             String sourceId,
                             String label,
                             String snippet) {
        return register(turnId, type, sourceId, label, snippet, Map.of());
    }

    /** 登记一条带元数据的不可变证据。 */
    public Evidence register(String turnId,
                             EvidenceType type,
                             String sourceId,
                             String label,
                             String snippet,
                             Map<String, String> metadata) {
        String effectiveTurnId = effectiveTurnId(turnId);
        TurnEvidence state = turns.computeIfAbsent(effectiveTurnId, ignored -> new TurnEvidence());
        int number = state.nextId.getAndIncrement();
        Evidence evidence = new Evidence(
                "E" + number,
                effectiveTurnId,
                type,
                sourceId,
                label,
                snippet,
                Instant.now(),
                metadata);
        state.evidences.add(evidence);
        return evidence;
    }

    /** 将 RAG 命中全部登记；去重与展示数量限制留给回答展示层处理。 */
    public List<Evidence> registerRagHits(String turnId, List<RagRecall.RecallHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<Evidence> result = new ArrayList<>();
        for (RagRecall.RecallHit hit : hits) {
            if (hit == null) {
                continue;
            }
            String label = hit.docName() == null || hit.docName().isBlank()
                    ? hit.effectiveSourceLabel() : hit.docName().trim();
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("score", Double.toString(hit.score()));
            metadata.put("source", hit.source() == null ? "" : hit.source().name());
            metadata.put("sourceLabel", hit.effectiveSourceLabel());
            if (hit.docId() != null) {
                metadata.put("docId", hit.docId());
            }
            if (hit.chunkIndex() != null) {
                metadata.put("chunkIndex", hit.chunkIndex().toString());
            }
            result.add(register(turnId, EvidenceType.RAG, hit.id(), label, hit.content(), metadata));
        }
        return List.copyOf(result);
    }

    /** 返回当前 turn 的稳定快照；调用方不能通过该列表修改 Registry。 */
    public List<Evidence> snapshot(String turnId) {
        TurnEvidence state = turns.get(effectiveTurnId(turnId));
        return state == null ? List.of() : List.copyOf(state.evidences);
    }

    /** 返回某个 Evidence ID；未知 ID 不会被隐式创建。 */
    public Evidence find(String turnId, String evidenceId) {
        return snapshot(turnId).stream()
                .filter(evidence -> evidence.evidenceId().equals(evidenceId))
                .findFirst()
                .orElse(null);
    }

    /** 清理已完成 turn，避免进程内 Registry 无限增长。 */
    public void clear(String turnId) {
        if (validTurnId(turnId)) {
            turns.remove(turnId);
        }
    }

    /**
     * 给工具 JSON 中的日志、状态、知识或工单记录登记 Evidence，并把 ID 回写给模型。
     * 原始字符串无法解析时仍登记一条工具结果，保证任何工具观察都有可追踪 ID。
     */
    public String registerToolResult(String turnId, String toolName, String rawResult) {
        if (!validTurnId(turnId) || rawResult == null || rawResult.isBlank()) {
            return rawResult;
        }
        try {
            JsonNode root = objectMapper.readTree(rawResult);
            if (!root.isObject()) {
                register(turnId, EvidenceType.TOOL_RESULT, toolName, toolName, rawResult);
                return rawResult;
            }
            ObjectNode enriched = (ObjectNode) root.deepCopy();
            boolean changed = false;
            changed |= enrichArray(turnId, toolName, enriched, "hits", typeFor(toolName, "hits"));
            changed |= enrichArray(turnId, toolName, enriched, "statuses", EvidenceType.STATUS_SNAPSHOT);
            changed |= enrichArray(turnId, toolName, enriched, "snapshots", EvidenceType.STATUS_SNAPSHOT);
            changed |= enrichArray(turnId, toolName, enriched, "tickets", EvidenceType.TICKET);
            changed |= enrichArray(turnId, toolName, enriched, "records", typeFor(toolName, "records"));
            if (!changed) {
                register(turnId, typeFor(toolName, "result"), toolName, toolName, rawResult);
                return rawResult;
            }
            return objectMapper.writeValueAsString(enriched);
        } catch (Exception e) {
            log.debug("[EvidenceRegistry] 工具结果不是可增强 JSON tool={}: {}", toolName, e.getMessage());
            register(turnId, typeFor(toolName, "result"), toolName, toolName, rawResult);
            return rawResult;
        }
    }

    private boolean enrichArray(String turnId,
                                String toolName,
                                ObjectNode root,
                                String field,
                                EvidenceType type) {
        JsonNode node = root.get(field);
        if (!(node instanceof ArrayNode array)) {
            return false;
        }
        boolean changed = false;
        int index = 0;
        for (JsonNode item : array) {
            if (!(item instanceof ObjectNode object)) {
                index++;
                continue;
            }
            String sourceId = firstText(object, "id", "traceId", "serviceName", "key");
            if (sourceId.isBlank()) {
                sourceId = toolName + ":" + field + ":" + index;
            }
            String label = firstText(object, "title", "serviceName", "path", "id");
            if (label.isBlank()) {
                label = toolName;
            }
            String snippet = firstText(object, "message", "summary", "detail", "content", "snippet", "excerpt", "title");
            if (snippet.isBlank()) {
                snippet = object.toString();
            }
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("tool", toolName);
            metadata.put("field", field);
            putMetadata(object, metadata, "url");
            putMetadata(object, metadata, "spaceName");
            putMetadata(object, metadata, "docId");
            Evidence evidence = register(turnId, type, sourceId, label, snippet, metadata);
            object.put("evidenceId", evidence.evidenceId());
            changed = true;
            index++;
        }
        return changed;
    }

    private EvidenceType typeFor(String toolName, String field) {
        String normalized = toolName == null ? "" : toolName.toLowerCase();
        if (normalized.contains("log")) {
            return EvidenceType.LOG;
        }
        if (normalized.contains("knowledge") || normalized.contains("rag")) {
            return EvidenceType.RAG;
        }
        if (normalized.contains("confluence")) {
            return EvidenceType.CONFLUENCE;
        }
        if (normalized.contains("ticket") || field.contains("ticket")) {
            return EvidenceType.TICKET;
        }
        return EvidenceType.TOOL_RESULT;
    }

    private void putMetadata(ObjectNode object, Map<String, String> metadata, String field) {
        String value = firstText(object, field);
        if (!value.isBlank()) {
            metadata.put(field, value);
        }
    }

    private String firstText(ObjectNode object, String... fields) {
        for (String field : fields) {
            JsonNode value = object.get(field);
            if (value != null && value.isValueNode() && !value.asText("").isBlank()) {
                return value.asText("").trim();
            }
        }
        return "";
    }

    private String effectiveTurnId(String turnId) {
        return validTurnId(turnId) ? turnId.trim() : ANONYMOUS_TURN;
    }

    private boolean validTurnId(String turnId) {
        return turnId != null && !turnId.isBlank();
    }

    private static final class TurnEvidence {
        private final AtomicInteger nextId = new AtomicInteger(1);
        private final List<Evidence> evidences = new CopyOnWriteArrayList<>();
    }
}
