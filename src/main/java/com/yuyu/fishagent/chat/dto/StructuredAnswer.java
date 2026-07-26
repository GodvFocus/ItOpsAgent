package com.yuyu.fishagent.chat.dto;

import java.util.List;

/**
 * 结构化回答主对象。
 * <p>模型先产出该对象，后端再渲染为 markdown，避免答案展示层继续依赖自由文本格式。</p>
 */
public record StructuredAnswer(
        Judgement judgement,
        List<AnswerItem> possibleCauses,
        List<AnswerStep> steps,
        List<AnswerItem> riskWarnings,
        List<String> missingInformation,
        List<SourceRef> evidences,
        String renderedMarkdown
) {

    public StructuredAnswer {
        judgement = judgement == null ? new Judgement("", List.of()) : judgement;
        possibleCauses = possibleCauses == null ? List.of() : List.copyOf(possibleCauses);
        steps = steps == null ? List.of() : List.copyOf(steps);
        riskWarnings = riskWarnings == null ? List.of() : List.copyOf(riskWarnings);
        missingInformation = missingInformation == null ? List.of() : List.copyOf(missingInformation);
        evidences = evidences == null ? List.of() : List.copyOf(evidences);
        renderedMarkdown = renderedMarkdown == null ? "" : renderedMarkdown;
    }

    public record Judgement(String summary, List<String> evidenceIds) {
        public Judgement {
            summary = summary == null ? "" : summary;
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    public record AnswerItem(String title, String detail, List<String> evidenceIds) {
        public AnswerItem {
            title = title == null ? "" : title;
            detail = detail == null ? "" : detail;
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    public record AnswerStep(String title, String detail, List<String> evidenceIds) {
        public AnswerStep {
            title = title == null ? "" : title;
            detail = detail == null ? "" : detail;
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }
}
