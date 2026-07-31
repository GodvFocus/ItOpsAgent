package com.ai.itops.chat.router;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 启发式 Query Router。
 *
 * <p>当前目标不是“完美分类”，而是先把明显的 SOP / Runbook 问答和明显的复杂故障排查拆开，
 * 让入口具备清晰边界，并且 trace 能解释“为什么这样分流”。</p>
 */
@Component
@RequiredArgsConstructor
public class HeuristicQueryRouter implements QueryRouter {

    private static final List<String> FAST_HINTS = List.of(
            "sop", "runbook", "文档", "说明", "步骤", "流程", "如何", "怎么", "是什么", "什么意思", "配置方法", "示例"
    );

    private static final List<String> AGENT_HINTS = List.of(
            "排查", "定位", "故障", "报错", "错误", "异常", "日志", "log", "trace",
            "状态", "health", "timeout", "timed out", "econnreset", "connection reset",
            "exception", "stacktrace", "堆栈", "告警", "失败", "redis", "mysql", "kafka"
    );

    private static final Pattern API_PATH = Pattern.compile("/[A-Za-z0-9_./-]{4,}");
    private static final Pattern CONFIG_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*\\.[A-Za-z0-9_.-]{2,}");
    private static final Pattern ERROR_CODE = Pattern.compile("\\b[A-Z][A-Z0-9_]{4,}\\b");
    private static final Pattern LOG_SHAPE = Pattern.compile("\\b(ERROR|WARN|INFO|DEBUG|Exception|Caused by)\\b", Pattern.CASE_INSENSITIVE);

    private final QueryRouterProperties properties;

    @Override
    public RouteDecision route(String userInput) {
        String normalized = normalize(userInput);
        if (!properties.isEnabled()) {
            return RouteDecision.troubleshootingAgent("router disabled");
        }
        if (normalized.isBlank()) {
            return RouteDecision.fastRag("empty input fallback");
        }

        List<String> reasons = new ArrayList<>();
        int agentScore = 0;
        for (String hint : AGENT_HINTS) {
            if (normalized.contains(hint)) {
                reasons.add("agent-hint:" + hint);
                agentScore++;
            }
        }
        for (String hint : FAST_HINTS) {
            if (normalized.contains(hint)) {
                reasons.add("fast-hint:" + hint);
            }
        }
        if (API_PATH.matcher(userInput).find()) {
            reasons.add("api-path");
            agentScore += 2;
        }
        if (CONFIG_KEY.matcher(userInput).find()) {
            reasons.add("config-key");
            agentScore += 2;
        }
        if (ERROR_CODE.matcher(userInput).find()) {
            reasons.add("error-code");
            agentScore += 2;
        }
        if (LOG_SHAPE.matcher(userInput).find()) {
            reasons.add("log-shape");
            agentScore += 2;
        }

        if (agentScore >= Math.max(1, properties.getAgentScoreThreshold())) {
            return RouteDecision.troubleshootingAgent("score=" + agentScore + ", reasons=" + String.join(",", reasons));
        }
        if (containsAny(normalized, FAST_HINTS)) {
            return RouteDecision.fastRag("matched fast hints: " + String.join(",", matched(normalized, FAST_HINTS)));
        }
        if (normalized.endsWith("?") || normalized.endsWith("？")) {
            return RouteDecision.fastRag("question-shaped input without troubleshooting signals");
        }
        return RouteDecision.fastRag("default fast-rag fallback");
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String normalized, List<String> hints) {
        for (String hint : hints) {
            if (normalized.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> matched(String normalized, List<String> hints) {
        List<String> out = new ArrayList<>();
        for (String hint : hints) {
            if (normalized.contains(hint)) {
                out.add(hint);
            }
        }
        return out;
    }
}
