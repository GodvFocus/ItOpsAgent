package com.ai.itops.agent.tool.troubleshooting;

import com.ai.itops.common.trace.TraceCollector;
import com.ai.itops.common.trace.TraceContext;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 排障工具公共能力：时间范围约束、limit 归一化、超时与审计。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TroubleshootingToolSupport {

    private static final DateTimeFormatter OUTPUT_TIME_FORMATTER =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

    private final TroubleshootingToolProperties properties;
    private final TraceCollector traceCollector;
    private final TroubleshootingSecurityGuard securityGuard;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    public TimeWindow resolveWindow(String startTime, String endTime) {
        Instant end = parseInstant(endTime);
        if (end == null) {
            end = Instant.now();
        }
        Instant start = parseInstant(startTime);
        if (start == null) {
            start = end.minus(Duration.ofHours(Math.max(1, properties.getDefaultLookbackHours())));
        }
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("endTime must be after startTime");
        }
        long hours = Math.max(1, Duration.between(start, end).toHours());
        if (hours > Math.max(1, properties.getMaxWindowHours())) {
            throw new IllegalArgumentException("time window exceeds maxWindowHours=" + properties.getMaxWindowHours());
        }
        return new TimeWindow(start, end, format(start), format(end));
    }

    public int resolveLimit(Integer requested) {
        int max = Math.max(1, properties.getMaxLimit());
        if (requested == null || requested <= 0) {
            return Math.min(Math.max(1, properties.getDefaultLimit()), max);
        }
        return Math.min(requested, max);
    }

    public <T> T execute(String toolName,
                         Object request,
                         Supplier<T> supplier,
                         Function<T, String> summaryFn,
                         Function<String, T> errorFactory) {
        long startNs = System.nanoTime();
        String turnId = TraceContext.currentTurnId();
        try {
            T result = CompletableFuture.supplyAsync(supplier, executor)
                    .orTimeout(Math.max(1L, properties.getTimeoutMs()), TimeUnit.MILLISECONDS)
                    .join();
            recordAudit(turnId, toolName, request, true, elapsedMs(startNs), safeSummary(summaryFn, result));
            return result;
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String message = cause instanceof TimeoutException
                    ? "timeout after " + properties.getTimeoutMs() + "ms"
                    : cause.getMessage();
            T fallback = errorFactory.apply(message == null ? "tool execution failed" : message);
            recordAudit(turnId, toolName, request, false, elapsedMs(startNs), message);
            return fallback;
        } catch (Exception e) {
            T fallback = errorFactory.apply(e.getMessage() == null ? "tool execution failed" : e.getMessage());
            recordAudit(turnId, toolName, request, false, elapsedMs(startNs), e.getMessage());
            return fallback;
        }
    }

    private void recordAudit(String turnId,
                             String toolName,
                             Object request,
                             boolean success,
                             long latencyMs,
                             String summary) {
        String safeRequest = securityGuard.redactForAudit(snippet(String.valueOf(request)));
        String safeSummary = securityGuard.redactForAudit(snippet(summary));
        String content = "tool=" + toolName
                + ", success=" + success
                + ", latencyMs=" + latencyMs
                + ", request=" + safeRequest
                + ", summary=" + safeSummary;
        log.info("[TroubleshootingToolAudit] {}", content);
        if (turnId != null && !turnId.isBlank()) {
            traceCollector.recordNode(turnId, "tool-audit", "system", content, latencyMs,
                    success ? "SUCCESS" : "ERROR");
        }
    }

    public String sanitizeEvidence(String value) {
        return securityGuard.sanitizeUntrustedEvidence(value);
    }

    public String redactForAudit(String value) {
        return securityGuard.redactForAudit(value);
    }

    public String trustLevel() {
        return securityGuard.trustLevel();
    }

    public String safetyReminder() {
        return securityGuard.reminder();
    }

    private static long elapsedMs(long startNs) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startNs));
    }

    private static String safeSummary(Function<?, String> summaryFn, Object result) {
        try {
            @SuppressWarnings("unchecked")
            Function<Object, String> fn = (Function<Object, String>) summaryFn;
            return fn.apply(result);
        } catch (Exception e) {
            return "summary unavailable";
        }
    }

    private static String snippet(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 180 ? value : value.substring(0, 180) + "...";
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignore) {
            return Instant.parse(value);
        }
    }

    private static String format(Instant instant) {
        return OUTPUT_TIME_FORMATTER.format(instant);
    }

    public record TimeWindow(Instant start, Instant end, String startText, String endText) {
    }
}
