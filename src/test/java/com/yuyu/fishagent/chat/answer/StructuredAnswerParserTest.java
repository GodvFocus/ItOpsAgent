package com.yuyu.fishagent.chat.answer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.chat.dto.SourceRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredAnswerParserTest {

    private final StructuredAnswerParser parser = new StructuredAnswerParser(new ObjectMapper());

    @Test
    void shouldParseValidStructuredAnswerAndKeepEvidenceLinks() {
        List<SourceRef> evidences = List.of(
                new SourceRef("课程笔记.pdf", SourceRef.Kind.DOC, "doc-1", 0, "订单服务重启 SOP", false, "", "E1"),
                new SourceRef("错误日志", SourceRef.Kind.PUBLIC, "doc-2", 3, "ECONNRESET", false, "", "E2")
        );
        String json = """
                {
                  "judgement": {
                    "summary": "当前更像是下游连接抖动。",
                    "evidenceIds": ["E1", "E2"]
                  },
                  "possibleCauses": [
                    {
                      "title": "连接被远端重置",
                      "detail": "日志里出现 ECONNRESET。",
                      "evidenceIds": ["E2"]
                    }
                  ],
                  "steps": [
                    {
                      "title": "先按 SOP 重启订单服务",
                      "detail": "重启后再做健康检查。",
                      "evidenceIds": ["E1"]
                    }
                  ],
                  "riskWarnings": [
                    {
                      "title": "避免直接全量重启",
                      "detail": "应优先灰度。",
                      "evidenceIds": []
                    }
                  ],
                  "missingInformation": ["缺少下游网络指标"]
                }
                """;

        var answer = parser.parse(json, evidences);

        assertThat(answer.judgement().summary()).contains("下游连接抖动");
        assertThat(answer.judgement().evidenceIds()).containsExactly("E1", "E2");
        assertThat(answer.possibleCauses()).hasSize(1);
        assertThat(answer.steps()).hasSize(1);
        assertThat(answer.riskWarnings()).hasSize(1);
        assertThat(answer.missingInformation()).containsExactly("缺少下游网络指标");
        assertThat(answer.evidences()).containsExactlyElementsOf(evidences);
    }

    @Test
    void shouldRejectUnknownEvidenceId() {
        List<SourceRef> evidences = List.of(
                new SourceRef("课程笔记.pdf", SourceRef.Kind.DOC, "doc-1", 0, "订单服务重启 SOP", false, "", "E1")
        );
        String json = """
                {
                  "judgement": {
                    "summary": "结论",
                    "evidenceIds": ["E9"]
                  },
                  "possibleCauses": [],
                  "steps": [],
                  "riskWarnings": [],
                  "missingInformation": []
                }
                """;

        assertThatThrownBy(() -> parser.parse(json, evidences))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown evidenceId=E9");
    }
}
