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
 * 服务状态检索工具。
 */
@Component
@RequiredArgsConstructor
public class ServiceStatusToolProvider implements AgentToolProvider {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

    private final TroubleshootingToolProperties properties;
    private final TroubleshootingToolSupport toolSupport;
    private final MockTroubleshootingDataService dataService;

    public record Input(String serviceName, String environment, String startTime, String endTime, Integer limit) {
    }

    public record StatusHit(String serviceName,
                            String environment,
                            String status,
                            String observedAt,
                            String summary,
                            List<String> symptoms,
                            List<String> indicators) {
    }

    public record Output(boolean ok,
                         String message,
                         String windowStart,
                         String windowEnd,
                         int total,
                         List<StatusHit> statuses) {
    }

    @Override
    public String name() {
        return "service_status_tool";
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
                    if (input == null || input.serviceName() == null || input.serviceName().isBlank()) {
                        return new Output(false, "serviceName is required", null, null, 0, List.of());
                    }
                    TroubleshootingToolSupport.TimeWindow window = toolSupport.resolveWindow(input.startTime(), input.endTime());
                    int limit = toolSupport.resolveLimit(input.limit());
                    List<StatusHit> statuses = dataService.searchServiceStatus(
                                    input.serviceName(), input.environment(), window, limit).stream()
                            .map(snapshot -> new StatusHit(
                                    snapshot.serviceName(),
                                    snapshot.environment(),
                                    snapshot.status(),
                                    TIME_FORMATTER.format(snapshot.observedAt()),
                                    snapshot.summary(),
                                    snapshot.symptoms(),
                                    snapshot.indicators()))
                            .toList();
                    return new Output(true, "ok", window.startText(), window.endText(), statuses.size(), statuses);
                },
                output -> "statuses=" + output.total(),
                error -> new Output(false, error, null, null, 0, List.of())
        );
        return FunctionToolCallback.builder(name(), fn)
                .description("查询指定服务在时间窗口内的状态快照。serviceName 必填；environment 可选；startTime/endTime 为 ISO-8601，默认最近 168 小时；limit 默认 5，最大 10。")
                .inputType(Input.class)
                .build();
    }
}
