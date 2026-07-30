package com.yuyu.fishagent.chat.answer;

import com.yuyu.fishagent.chat.dto.SourceRef;
import com.yuyu.fishagent.chat.dto.StructuredAnswer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 计算结构化回答的引用质量指标。
 *
 * <p>precision 检查引用 ID 是否来自 Evidence Registry，coverage 检查每个结构化 claim 是否至少有一个引用，
 * unsupported claim rate 则是没有引用的 claim 占比。</p>
 */
public final class CitationQualityEvaluator {

    private CitationQualityEvaluator() {
    }

    public static CitationQuality evaluate(StructuredAnswer answer) {
        if (answer == null) {
            return new CitationQuality(0, 0, 0, 0, 0, 0);
        }
        Set<String> allowed = answer.evidences() == null ? Set.of() : answer.evidences().stream()
                .map(SourceRef::evidenceId)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<List<String>> claims = claims(answer);
        int claimCount = claims.size();
        int supportedClaims = (int) claims.stream().filter(ids -> ids != null && !ids.isEmpty()).count();
        int totalCitations = claims.stream().mapToInt(ids -> ids == null ? 0 : ids.size()).sum();
        int validCitations = claims.stream()
                .flatMap(List::stream)
                .filter(allowed::contains)
                .mapToInt(ignored -> 1)
                .sum();
        double precision = totalCitations == 0 ? 0.0 : validCitations / (double) totalCitations;
        double coverage = claimCount == 0 ? 0.0 : supportedClaims / (double) claimCount;
        return new CitationQuality(precision, coverage, 1.0 - coverage,
                claimCount, supportedClaims, totalCitations);
    }

    private static List<List<String>> claims(StructuredAnswer answer) {
        java.util.ArrayList<List<String>> claims = new java.util.ArrayList<>();
        if (answer.judgement() != null && !answer.judgement().summary().isBlank()) {
            claims.add(answer.judgement().evidenceIds());
        }
        if (answer.possibleCauses() != null) {
            answer.possibleCauses().forEach(item -> claims.add(item.evidenceIds()));
        }
        if (answer.steps() != null) {
            answer.steps().forEach(item -> claims.add(item.evidenceIds()));
        }
        if (answer.riskWarnings() != null) {
            answer.riskWarnings().forEach(item -> claims.add(item.evidenceIds()));
        }
        return List.copyOf(claims);
    }

    public record CitationQuality(
            double precision,
            double coverage,
            double unsupportedClaimRate,
            int claimCount,
            int supportedClaimCount,
            int citationCount
    ) {
    }
}
