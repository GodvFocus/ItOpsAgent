package com.yuyu.fishagent.chat.answer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.chat.dto.SourceRef;
import com.yuyu.fishagent.chat.dto.StructuredAnswer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 严格解析结构化回答 JSON，并校验证据引用是否合法。
 */
@Component
@RequiredArgsConstructor
public class StructuredAnswerParser {

    private final ObjectMapper objectMapper;

    public StructuredAnswer parse(String rawText, List<SourceRef> evidences) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("structured answer output cannot be empty");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(stripCodeFence(rawText));
        } catch (Exception e) {
            throw new IllegalArgumentException("structured answer output is not valid JSON", e);
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("structured answer output must be a JSON object");
        }
        Set<String> allowedEvidenceIds = new LinkedHashSet<>();
        for (SourceRef evidence : evidences == null ? List.<SourceRef>of() : evidences) {
            if (evidence != null && evidence.evidenceId() != null && !evidence.evidenceId().isBlank()) {
                allowedEvidenceIds.add(evidence.evidenceId().trim());
            }
        }

        StructuredAnswer.Judgement judgement = parseJudgement(root.get("judgement"), allowedEvidenceIds);
        if (judgement.summary().isBlank()) {
            throw new IllegalArgumentException("structured answer judgement.summary cannot be blank");
        }
        return new StructuredAnswer(
                judgement,
                parseAnswerItems(root.get("possibleCauses"), allowedEvidenceIds),
                parseSteps(root.get("steps"), allowedEvidenceIds),
                parseAnswerItems(root.get("riskWarnings"), allowedEvidenceIds),
                parseStringList(root.get("missingInformation")),
                evidences,
                ""
        );
    }

    private StructuredAnswer.Judgement parseJudgement(JsonNode node, Set<String> allowedEvidenceIds) {
        if (node == null || node.isNull()) {
            return new StructuredAnswer.Judgement("", List.of());
        }
        if (node.isTextual()) {
            return new StructuredAnswer.Judgement(node.asText("").trim(), List.of());
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("judgement must be an object");
        }
        return new StructuredAnswer.Judgement(
                node.path("summary").asText("").trim(),
                parseEvidenceIds(node.get("evidenceIds"), allowedEvidenceIds)
        );
    }

    private List<StructuredAnswer.AnswerItem> parseAnswerItems(JsonNode node, Set<String> allowedEvidenceIds) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<StructuredAnswer.AnswerItem> items = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || item.isNull()) {
                continue;
            }
            if (item.isTextual()) {
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    items.add(new StructuredAnswer.AnswerItem(text, "", List.of()));
                }
                continue;
            }
            if (!item.isObject()) {
                throw new IllegalArgumentException("answer item must be an object");
            }
            String title = item.path("title").asText("").trim();
            String detail = item.path("detail").asText("").trim();
            if (title.isEmpty() && detail.isEmpty()) {
                continue;
            }
            items.add(new StructuredAnswer.AnswerItem(
                    title,
                    detail,
                    parseEvidenceIds(item.get("evidenceIds"), allowedEvidenceIds)
            ));
        }
        return List.copyOf(items);
    }

    private List<StructuredAnswer.AnswerStep> parseSteps(JsonNode node, Set<String> allowedEvidenceIds) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<StructuredAnswer.AnswerStep> steps = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String title = item.path("title").asText("").trim();
            String detail = item.path("detail").asText("").trim();
            if (title.isEmpty() && detail.isEmpty()) {
                continue;
            }
            steps.add(new StructuredAnswer.AnswerStep(
                    title,
                    detail,
                    parseEvidenceIds(item.get("evidenceIds"), allowedEvidenceIds)
            ));
        }
        return List.copyOf(steps);
    }

    private List<String> parseEvidenceIds(JsonNode node, Set<String> allowedEvidenceIds) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                continue;
            }
            String evidenceId = item.asText("").trim();
            if (evidenceId.isEmpty()) {
                continue;
            }
            if (!allowedEvidenceIds.contains(evidenceId)) {
                throw new IllegalArgumentException("structured answer references unknown evidenceId=" + evidenceId);
            }
            ids.add(evidenceId);
        }
        return List.copyOf(ids);
    }

    private List<String> parseStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                continue;
            }
            String value = item.asText("").trim();
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return List.copyOf(result);
    }

    private String stripCodeFence(String rawText) {
        String text = rawText.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        int lastFenceStart = text.lastIndexOf("```");
        if (firstLineEnd < 0 || lastFenceStart <= firstLineEnd) {
            return text;
        }
        return text.substring(firstLineEnd + 1, lastFenceStart).trim();
    }
}
