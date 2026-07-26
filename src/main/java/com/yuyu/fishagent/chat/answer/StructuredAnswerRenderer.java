package com.yuyu.fishagent.chat.answer;

import com.yuyu.fishagent.chat.dto.StructuredAnswer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 将结构化回答渲染为稳定 markdown。
 * <p>后端统一负责展示模板，前端既可直接渲染 markdown，也可使用结构字段做更细粒度 UI。</p>
 */
@Component
public class StructuredAnswerRenderer {

    public String render(StructuredAnswer answer) {
        if (answer == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendSectionTitle(sb, "判断");
        appendParagraph(sb, answer.judgement().summary(), answer.judgement().evidenceIds());
        appendItemSection(sb, "可能原因", answer.possibleCauses());
        appendStepSection(sb, answer.steps());
        appendItemSection(sb, "风险提示", answer.riskWarnings());
        appendMissingSection(sb, answer.missingInformation());
        return sb.toString().trim();
    }

    private void appendSectionTitle(StringBuilder sb, String title) {
        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        sb.append("## ").append(title).append('\n');
    }

    private void appendParagraph(StringBuilder sb, String text, List<String> evidenceIds) {
        if (text != null && !text.isBlank()) {
            sb.append(text.trim());
            String refs = renderEvidenceRefs(evidenceIds);
            if (!refs.isEmpty()) {
                sb.append(' ').append(refs);
            }
        } else {
            sb.append("暂无明确结论");
        }
    }

    private void appendItemSection(StringBuilder sb, String title, List<StructuredAnswer.AnswerItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        appendSectionTitle(sb, title);
        int index = 1;
        for (StructuredAnswer.AnswerItem item : items) {
            String main = item.title().isBlank() ? item.detail().trim() : "**" + item.title().trim() + "**";
            if (!item.detail().isBlank() && !item.title().isBlank()) {
                main += "：" + item.detail().trim();
            }
            String refs = renderEvidenceRefs(item.evidenceIds());
            if (!refs.isEmpty()) {
                main += " " + refs;
            }
            sb.append(index++).append(". ").append(main).append('\n');
        }
        trimTrailingNewline(sb);
    }

    private void appendStepSection(StringBuilder sb, List<StructuredAnswer.AnswerStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        appendSectionTitle(sb, "建议步骤");
        int index = 1;
        for (StructuredAnswer.AnswerStep step : steps) {
            String main = step.title().isBlank() ? step.detail().trim() : "**" + step.title().trim() + "**";
            if (!step.detail().isBlank() && !step.title().isBlank()) {
                main += "：" + step.detail().trim();
            }
            String refs = renderEvidenceRefs(step.evidenceIds());
            if (!refs.isEmpty()) {
                main += " " + refs;
            }
            sb.append(index++).append(". ").append(main).append('\n');
        }
        trimTrailingNewline(sb);
    }

    private void appendMissingSection(StringBuilder sb, List<String> missingInformation) {
        if (missingInformation == null || missingInformation.isEmpty()) {
            return;
        }
        appendSectionTitle(sb, "缺失信息");
        int index = 1;
        for (String item : missingInformation) {
            if (item == null || item.isBlank()) {
                continue;
            }
            sb.append(index++).append(". ").append(item.trim()).append('\n');
        }
        trimTrailingNewline(sb);
    }

    private String renderEvidenceRefs(List<String> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return "";
        }
        return "（证据：" + evidenceIds.stream().collect(Collectors.joining(", ")) + "）";
    }

    private void trimTrailingNewline(StringBuilder sb) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.deleteCharAt(sb.length() - 1);
        }
    }
}
