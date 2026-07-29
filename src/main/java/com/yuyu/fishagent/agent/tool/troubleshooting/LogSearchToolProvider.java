package com.yuyu.fishagent.agent.tool.troubleshooting;

import com.yuyu.fishagent.agent.tool.AgentToolProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

/**
 * 日志检索工具。
 */
@Component
@RequiredArgsConstructor
public class LogSearchToolProvider implements AgentToolProvider {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

    private final TroubleshootingToolProperties properties;
    private final TroubleshootingToolSupport toolSupport;
    private final MockTroubleshootingDataService dataService;

    public record Input(String query, String serviceName, String level, String startTime, String endTime, Integer limit) {
    }

    public record LogHit(String id,
                         String serviceName,
                         String level,
                         String observedAt,
                         String traceId,
                         String path,
                         String message) {
    }

    public record Output(boolean ok,
                         String message,
                         boolean untrustedInput,
                         String trustLevel,
                         String safetyReminder,
                         String windowStart,
                         String windowEnd,
                         int total,
                         List<LogHit> hits) {
    }

    @Override
    public String name() {
        return "log_search_tool";
    }

    @Override
    public boolean enabled() {
        return properties.isEnabled();
    }

    @Override
    public ToolCallback build() {
        Function<Input, Output> fn = input -> toolSupport.execute(
                name(),
                input,
                () -> {
                    TroubleshootingToolSupport.TimeWindow window = toolSupport.resolveWindow(
                            input == null ? null : input.startTime(),
                            input == null ? null : input.endTime());
                    int limit = toolSupport.resolveLimit(input == null ? null : input.limit());
                    List<LogHit> hits = dataService.searchLogs(
                                    input == null ? null : input.query(),
                                    input == null ? null : input.serviceName(),
                                    input == null ? null : input.level(),
                                    window,
                                    limit).stream()
                            .map(log -> new LogHit(
                                    log.id(),
                                    log.serviceName(),
                                    log.level(),
                                    TIME_FORMATTER.format(log.observedAt()),
                                    log.traceId(),
                                    toolSupport.sanitizeEvidence(log.path()),
                                    toolSupport.sanitizeEvidence(log.message())))
                            .toList();
                    return new Output(true, "ok", true, toolSupport.trustLevel(), toolSupport.safetyReminder(),
                            window.startText(), window.endText(), hits.size(), hits);
                },
                output -> "hits=" + output.total(),
                error -> new Output(false, toolSupport.redactForAudit(error), true, toolSupport.trustLevel(),
                        toolSupport.safetyReminder(), null, null, 0, List.of())
        );
        return FunctionToolCallback.builder(name(), fn)
                .description("按关键词、服务名、级别检索故障日志。query 可选但建议提供错误码/路径/异常特征；serviceName、level 可选；startTime/endTime 为 ISO-8601，默认最近 168 小时；limit 默认 5，最大 10。")
                .inputType(Input.class)
                .build();
    }
}
