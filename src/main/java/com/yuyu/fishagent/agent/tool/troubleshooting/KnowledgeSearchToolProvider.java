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
 * 排障知识检索工具。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeSearchToolProvider implements AgentToolProvider {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

    private final TroubleshootingToolProperties properties;
    private final TroubleshootingToolSupport toolSupport;
    private final MockTroubleshootingDataService dataService;

    public record Input(String query, String serviceName, String startTime, String endTime, Integer limit) {
    }

    public record Hit(String id, String title, String summary, String serviceName, String updatedAt, List<String> tags) {
    }

    public record Output(boolean ok,
                         String message,
                         String windowStart,
                         String windowEnd,
                         int total,
                         List<Hit> hits) {
    }

    @Override
    public String name() {
        return "knowledge_search_tool";
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
                    if (input == null || input.query() == null || input.query().isBlank()) {
                        return new Output(false, "query is required", null, null, 0, List.of());
                    }
                    TroubleshootingToolSupport.TimeWindow window = toolSupport.resolveWindow(input.startTime(), input.endTime());
                    int limit = toolSupport.resolveLimit(input.limit());
                    List<Hit> hits = dataService.searchKnowledge(input.query(), input.serviceName(), window, limit).stream()
                            .map(doc -> new Hit(
                                    doc.id(),
                                    doc.title(),
                                    doc.summary(),
                                    doc.serviceName(),
                                    TIME_FORMATTER.format(doc.updatedAt()),
                                    doc.tags()))
                            .toList();
                    return new Output(true, "ok", window.startText(), window.endText(), hits.size(), hits);
                },
                output -> "hits=" + output.total(),
                error -> new Output(false, error, null, null, 0, List.of())
        );
        return FunctionToolCallback.builder(name(), fn)
                .description("检索排障知识与 Runbook。适合查 SOP、错误码、接口路径、配置项。query 必填；serviceName 可选；startTime/endTime 为 ISO-8601，默认最近 168 小时；limit 默认 5，最大 10。")
                .inputType(Input.class)
                .build();
    }
}
