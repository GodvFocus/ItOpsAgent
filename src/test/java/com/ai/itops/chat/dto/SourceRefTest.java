package com.ai.itops.chat.dto;

import com.ai.itops.chat.dto.SourceRef.Kind;
import com.ai.itops.rag.pipeline.recall.RagRecall.RecallHit;
import com.ai.itops.rag.pipeline.recall.RagRecall.RecallSource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceRefTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-27T06:00:00Z"), ZONE);

    @Test
    void memoryHitIsKindMemoryWithAge() {
        long createdAt = Instant.parse("2026-06-24T06:00:00Z").toEpochMilli();
        RecallHit hit = new RecallHit("m1", "我用 Rust 维护 Fish-Agent", 0.9,
                RecallSource.TEXT, "记忆", 0.7, createdAt, null, null);

        SourceRef ref = SourceRef.from(hit, CLOCK);

        assertThat(ref.kind()).isEqualTo(Kind.MEMORY);
        assertThat(ref.memory()).isTrue();
        assertThat(ref.label()).isEqualTo("记忆");
        assertThat(ref.timeText()).isNotBlank();
        assertThat(ref.evidenceId()).isEqualTo("E1");
    }

    @Test
    void userDocumentHitIsKindDocAndShowsDocName() {
        RecallHit hit = new RecallHit("k1", "第 1 章讲反向传播", 0.8,
                RecallSource.TEXT, "用户", 1.0, Instant.parse("2026-05-10T00:00:00Z").toEpochMilli(),
                "task-1", 3, "课程笔记.pdf");

        SourceRef ref = SourceRef.from(hit, CLOCK);

        assertThat(ref.kind()).isEqualTo(Kind.DOC);
        assertThat(ref.memory()).isFalse();
        assertThat(ref.label()).isEqualTo("课程笔记.pdf");
        assertThat(ref.docId()).isEqualTo("task-1");
        assertThat(ref.timeText()).isEmpty();
        assertThat(ref.evidenceId()).isEqualTo("E1");
    }

    @Test
    void cardHitIsKindCardEvenThoughSourceLabelIsUser() {
        RecallHit hit = new RecallHit("card:42", "JVM 内存模型要点", 0.75,
                RecallSource.TEXT, "用户", 0.7, null, null, null, "JVM 内存模型");

        SourceRef ref = SourceRef.from(hit, CLOCK);

        assertThat(ref.kind()).isEqualTo(Kind.CARD);
        assertThat(ref.memory()).isFalse();
        assertThat(ref.label()).isEqualTo("JVM 内存模型");
    }

    @Test
    void publicHitIsKindPublic() {
        RecallHit hit = new RecallHit("p1", "公开规范内容", 0.7,
                RecallSource.TEXT, "公开", 1.0, null, "pub-1", 0, "行业规范.pdf");

        SourceRef ref = SourceRef.from(hit, CLOCK);

        assertThat(ref.kind()).isEqualTo(Kind.PUBLIC);
        assertThat(ref.label()).isEqualTo("行业规范.pdf");
    }

    @Test
    void longContentIsTruncatedWithEllipsis() {
        String longContent = "a".repeat(200);
        RecallHit hit = new RecallHit("k3", longContent, 0.5,
                RecallSource.TEXT, "用户", 1.0, null, "t", 0, "doc.pdf");

        SourceRef ref = SourceRef.from(hit, CLOCK);

        assertThat(ref.snippet()).hasSize(103).endsWith("...");
    }

    @Test
    void fromListDedupsByLabelKeepingHighestScoreAndAssignsEvidenceIds() {
        RecallHit d1a = new RecallHit("a", "片段1", 0.9, RecallSource.TEXT, "用户", 0.7,
                null, "t1", 0, "课程笔记.pdf");
        RecallHit d1b = new RecallHit("b", "片段2", 0.85, RecallSource.TEXT, "用户", 0.7,
                null, "t1", 1, "课程笔记.pdf");
        RecallHit d2 = new RecallHit("c", "片段3", 0.7, RecallSource.TEXT, "用户", 0.7,
                null, "t2", 0, "论文.pdf");

        List<SourceRef> refs = SourceRef.from(List.of(d1a, d1b, d2), CLOCK);

        assertThat(refs).hasSize(2);
        assertThat(refs.get(0).label()).isEqualTo("课程笔记.pdf");
        assertThat(refs.get(0).snippet()).isEqualTo("片段1");
        assertThat(refs.get(0).evidenceId()).isEqualTo("E1");
        assertThat(refs.get(1).label()).isEqualTo("论文.pdf");
        assertThat(refs.get(1).evidenceId()).isEqualTo("E2");
        assertThat(refs).allSatisfy(r -> assertThat(r.kind()).isEqualTo(Kind.DOC));
    }

    @Test
    void fromListCapsAtMaxSources() {
        List<RecallHit> hits = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            hits.add(new RecallHit("id" + i, "c" + i, 0.9 - i * 0.01, RecallSource.TEXT,
                    "用户", 0.7, null, "t" + i, 0, "doc" + i + ".pdf"));
        }

        List<SourceRef> refs = SourceRef.from(hits, CLOCK);

        assertThat(refs).hasSize(5);
        assertThat(refs.get(0).label()).isEqualTo("doc0.pdf");
        assertThat(refs.get(4).evidenceId()).isEqualTo("E5");
    }

    @Test
    void fromListHandlesEmptyAndNull() {
        assertThat(SourceRef.from(List.<RecallHit>of(), CLOCK)).isEmpty();
        assertThat(SourceRef.from((List<RecallHit>) null, CLOCK)).isEmpty();
    }
}
