package com.ai.itops.chat.dto;

import com.ai.itops.common.evidence.Evidence;
import com.ai.itops.common.evidence.EvidenceType;
import com.ai.itops.rag.pipeline.recall.MemoryAgeLabel;
import com.ai.itops.rag.pipeline.recall.RagRecall.RecallHit;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 回答证据引用。
 * <p>把 RAG 召回命中整理成前后端统一消费的证据模型，并为每条证据分配稳定的 {@code evidenceId}。</p>
 */
public record SourceRef(
        String label,
        Kind kind,
        String docId,
        Integer chunkIndex,
        String snippet,
        boolean memory,
        String timeText,
        String evidenceId,
        String url
) {

    /** 保留旧调用方构造方式，新增 URL 默认为空。 */
    public SourceRef(String label,
                     Kind kind,
                     String docId,
                     Integer chunkIndex,
                     String snippet,
                     boolean memory,
                     String timeText,
                     String evidenceId) {
        this(label, kind, docId, chunkIndex, snippet, memory, timeText, evidenceId, "");
    }

    public enum Kind {
        MEMORY, DOC, CARD, PUBLIC, LOG, STATUS, TICKET, CONFLUENCE, TOOL
    }

    private static final int SNIPPET_MAX = 100;
    private static final int MAX_SOURCES = 5;
    private static final String MEMORY_LABEL = "记忆";

    public static SourceRef from(RecallHit hit) {
        return from(hit, Clock.systemDefaultZone(), "E1");
    }

    public static SourceRef from(RecallHit hit, Clock clock) {
        return from(hit, clock, "E1");
    }

    public static List<SourceRef> from(List<RecallHit> hits) {
        return from(hits, Clock.systemDefaultZone());
    }

    public static List<SourceRef> from(List<RecallHit> hits, Clock clock) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<SourceRef> out = new ArrayList<>();
        Set<String> seenLabels = new HashSet<>();
        int nextEvidenceNo = 1;
        for (RecallHit hit : hits) {
            SourceRef ref = from(hit, clock, "E" + nextEvidenceNo);
            if (seenLabels.add(ref.label())) {
                out.add(ref);
                nextEvidenceNo++;
                if (out.size() >= MAX_SOURCES) {
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    /** 将统一 Registry 中的证据映射为前后端共用的来源引用。 */
    public static SourceRef from(Evidence evidence) {
        if (evidence == null) {
            return null;
        }
        Kind kind = switch (evidence.type()) {
            case RAG -> kindFromRag(evidence);
            case LOG -> Kind.LOG;
            case STATUS_SNAPSHOT -> Kind.STATUS;
            case TICKET -> Kind.TICKET;
            case CONFLUENCE -> Kind.CONFLUENCE;
            case TOOL_RESULT -> Kind.TOOL;
        };
        boolean memory = "记忆".equals(evidence.metadata().get("sourceLabel"));
        return new SourceRef(
                evidence.label().isBlank() ? evidence.type().name() : evidence.label(),
                memory ? Kind.MEMORY : kind,
                evidence.sourceId(),
                integerMetadata(evidence, "chunkIndex"),
                snippet(evidence.snippet()),
                memory,
                evidence.metadata().getOrDefault("timeText", ""),
                evidence.evidenceId(),
                evidence.metadata().getOrDefault("url", ""));
    }

    private static Kind kindFromRag(Evidence evidence) {
        if (evidence.sourceId().startsWith("card:")) {
            return Kind.CARD;
        }
        String label = evidence.metadata().getOrDefault("sourceLabel", "");
        return "公开".equals(label) || "官方".equals(label) ? Kind.PUBLIC : Kind.DOC;
    }

    private static Integer integerMetadata(Evidence evidence, String key) {
        try {
            String value = evidence.metadata().get(key);
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static SourceRef from(RecallHit hit, Clock clock, String evidenceId) {
        String sourceLabel = hit.sourceLabel();
        boolean isMemory = MEMORY_LABEL.equals(sourceLabel);
        Kind kind;
        if (isMemory) {
            kind = Kind.MEMORY;
        } else if (hit.id() != null && hit.id().startsWith("card:")) {
            kind = Kind.CARD;
        } else if ("公开".equals(sourceLabel) || "官方".equals(sourceLabel)) {
            kind = Kind.PUBLIC;
        } else {
            kind = Kind.DOC;
        }
        String label = (hit.docName() != null && !hit.docName().isBlank())
                ? hit.docName().trim()
                : hit.effectiveSourceLabel();
        return new SourceRef(
                label,
                kind,
                hit.docId(),
                hit.chunkIndex(),
                snippet(hit.content()),
                isMemory,
                isMemory ? MemoryAgeLabel.format(hit.createdAt(), clock) : "",
                evidenceId
        );
    }

    private static String snippet(String content) {
        if (content == null) {
            return "";
        }
        String flat = content.replace("\r\n", "\n").replace("\n", " ").trim();
        return flat.length() <= SNIPPET_MAX ? flat : flat.substring(0, SNIPPET_MAX) + "...";
    }
}
