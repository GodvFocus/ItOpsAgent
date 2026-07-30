package com.yuyu.fishagent.chat.answer;

import com.yuyu.fishagent.chat.dto.SourceRef;
import com.yuyu.fishagent.chat.dto.StructuredAnswer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationQualityEvaluatorTest {

    @Test
    void shouldMeasurePrecisionCoverageAndUnsupportedClaimRate() {
        StructuredAnswer answer = new StructuredAnswer(
                new StructuredAnswer.Judgement("判断", List.of("E1")),
                List.of(new StructuredAnswer.AnswerItem("原因", "细节", List.of())),
                List.of(new StructuredAnswer.AnswerStep("步骤", "执行", List.of("E2"))),
                List.of(),
                List.of(),
                List.of(new SourceRef("日志", SourceRef.Kind.LOG, "log-1", null, "错误", false, "", "E1"),
                        new SourceRef("状态", SourceRef.Kind.STATUS, "order", null, "异常", false, "", "E2")),
                "");

        CitationQualityEvaluator.CitationQuality quality = CitationQualityEvaluator.evaluate(answer);

        assertThat(quality.precision()).isEqualTo(1.0);
        assertThat(quality.coverage()).isCloseTo(2.0 / 3.0,
                org.assertj.core.data.Offset.offset(0.0000001));
        assertThat(quality.unsupportedClaimRate()).isCloseTo(1.0 / 3.0,
                org.assertj.core.data.Offset.offset(0.0000001));
        assertThat(quality.claimCount()).isEqualTo(3);
        assertThat(quality.citationCount()).isEqualTo(2);
    }
}
