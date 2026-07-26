package com.yuyu.fishagent.chat.dto;

import com.yuyu.fishagent.rag.pipeline.recall.MemoryAgeLabel;
import com.yuyu.fishagent.rag.pipeline.recall.RagRecall.RecallHit;

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
        String evidenceId
) {

    public enum Kind {
        MEMORY, DOC, CARD, PUBLIC
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
