package com.yuyu.fishagent.common.evidence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单条不可变证据。
 *
 * <p>Evidence 一经登记不再修改；模型只拿到 {@link #evidenceId()}，最终回答也只能引用已登记的 ID。</p>
 */
public record Evidence(
        String evidenceId,
        String turnId,
        EvidenceType type,
        String sourceId,
        String label,
        String snippet,
        Instant registeredAt,
        Map<String, String> metadata
) {

    public Evidence {
        evidenceId = required(evidenceId, "evidenceId");
        turnId = required(turnId, "turnId");
        type = type == null ? EvidenceType.TOOL_RESULT : type;
        sourceId = sourceId == null ? "" : sourceId.trim();
        label = label == null ? "" : label.trim();
        snippet = snippet == null ? "" : snippet.trim();
        registeredAt = registeredAt == null ? Instant.now() : registeredAt;
        metadata = metadata == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value.trim();
    }
}
