package com.ai.itops.agent.tool.confluence;

import com.ai.itops.common.resilience.CircuitBreakerHelper;
import com.ai.itops.common.resilience.ResilienceConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Confluence REST API 适配器。
 *
 * <p>只允许通过配置的根地址访问，工具输入不能传入任意 URL，避免把它变成 SSRF 入口。</p>
 */
@Slf4j
@Component
public class ConfluenceRestClient implements ConfluenceClient {

    private final ConfluenceProperties properties;
    private final ConfluenceDocumentConverter converter;
    private final CircuitBreakerHelper circuitBreakerHelper;
    private final RestClient restClient;

    public ConfluenceRestClient(ConfluenceProperties properties,
                                ConfluenceDocumentConverter converter,
                                CircuitBreakerHelper circuitBreakerHelper) {
        this.properties = properties;
        this.converter = converter;
        this.circuitBreakerHelper = circuitBreakerHelper;
        this.restClient = buildClient(properties);
    }

    @Override
    public SearchResponse search(String query, String spaceKey, Integer requestedLimit) {
        ensureConfigured();
        String normalizedQuery = normalizeQuery(query);
        int limit = resolveLimit(requestedLimit);
        String cql = buildCql(normalizedQuery, spaceKey);
        URI uri = UriComponentsBuilder.fromUriString(properties.getBaseUrl().trim())
                .path("/rest/api/content/search")
                .queryParam("cql", cql)
                .queryParam("limit", limit)
                .queryParam("expand", "space,version")
                .build()
                .encode()
                .toUri();
        return circuitBreakerHelper.executeWithCircuitBreaker(
                ResilienceConstants.CB_CONFLUENCE,
                () -> parseSearch(uri),
                SearchResponse.empty("Confluence 暂时不可用"));
    }

    @Override
    public DocumentResponse fetch(String contentId) {
        ensureConfigured();
        String normalizedId = normalizeContentId(contentId);
        URI uri = UriComponentsBuilder.fromUriString(properties.getBaseUrl().trim())
                .path("/rest/api/content/")
                .pathSegment(normalizedId)
                .queryParam("expand", "body.storage,space,version")
                .build()
                .encode()
                .toUri();
        return circuitBreakerHelper.executeWithCircuitBreaker(
                ResilienceConstants.CB_CONFLUENCE,
                () -> parseDocument(uri, normalizedId),
                DocumentResponse.empty("Confluence 暂时不可用"));
    }

    private SearchResponse parseSearch(URI uri) {
        JsonNode root = restClient.get()
                .uri(uri)
                .headers(this::applyAuth)
                .retrieve()
                .body(JsonNode.class);
        List<SearchHit> hits = new ArrayList<>();
        JsonNode results = root == null ? null : root.path("results");
        if (results != null && results.isArray()) {
            for (JsonNode item : results) {
                String id = text(item, "id");
                if (id.isBlank()) {
                    continue;
                }
                hits.add(new SearchHit(
                        id,
                        text(item, "title"),
                        text(item.path("space"), "name"),
                        cleanSnippet(text(item, "excerpt")),
                        pageUrl(item, root)));
            }
        }
        return new SearchResponse(true, hits.isEmpty() ? "未找到匹配的 Confluence 页面" : "ok", hits);
    }

    private DocumentResponse parseDocument(URI uri, String contentId) {
        JsonNode root = restClient.get()
                .uri(uri)
                .headers(this::applyAuth)
                .retrieve()
                .body(JsonNode.class);
        if (root == null || root.isMissingNode()) {
            return DocumentResponse.empty("Confluence 页面为空");
        }
        String html = root.path("body").path("storage").path("value").asText("");
        String content = converter.convert(html, properties.getMaxContentChars());
        Document document = new Document(
                contentId,
                text(root, "title"),
                text(root.path("space"), "name"),
                pageUrl(root, root),
                content);
        return new DocumentResponse(true, content.isBlank() ? "页面正文为空" : "ok", List.of(document));
    }

    private void applyAuth(HttpHeaders headers) {
        String authType = properties.getAuthType() == null
                ? "NONE" : properties.getAuthType().trim().toUpperCase(Locale.ROOT);
        if (("BEARER".equals(authType) || "API_TOKEN".equals(authType))
                && hasText(properties.getToken())) {
            headers.setBearerAuth(properties.getToken().trim());
        } else if ("BASIC".equals(authType)
                && hasText(properties.getUsername())
                && properties.getPassword() != null) {
            headers.setBasicAuth(properties.getUsername().trim(), properties.getPassword());
        }
        headers.set("Accept", "application/json");
    }

    private String buildCql(String query, String requestedSpace) {
        String escaped = query.replace("\\", "\\\\").replace("\"", "\\\"");
        StringBuilder cql = new StringBuilder("type = page AND status = current AND (title ~ \"")
                .append(escaped).append("\" OR text ~ \"").append(escaped).append("\")");
        String space = resolveSpace(requestedSpace);
        if (space != null && !space.isBlank()) {
            List<String> keys = java.util.Arrays.stream(space.split(","))
                    .map(String::trim)
                    .filter(value -> value.matches("[A-Za-z0-9_-]{1,64}"))
                    .map(value -> "\"" + value + "\"")
                    .toList();
            if (!keys.isEmpty()) {
                cql.append(" AND space in (").append(String.join(",", keys)).append(')');
            }
        }
        return cql.toString();
    }

    private String resolveSpace(String requestedSpace) {
        String configured = properties.getSpaceKeys();
        if (configured == null || configured.isBlank()) {
            return requestedSpace;
        }
        List<String> allowed = java.util.Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> value.matches("[A-Za-z0-9_-]{1,64}"))
                .toList();
        if (requestedSpace == null || requestedSpace.isBlank()) {
            return String.join(",", allowed);
        }
        String requested = requestedSpace.trim();
        if (!allowed.contains(requested)) {
            throw new IllegalArgumentException("spaceKey is outside configured Confluence allowlist");
        }
        return requested;
    }

    private String normalizeQuery(String query) {
        if (!hasText(query)) {
            throw new IllegalArgumentException("query is required");
        }
        String normalized = query.trim();
        int max = Math.max(1, properties.getMaxQueryChars());
        if (normalized.length() > max) {
            throw new IllegalArgumentException("query exceeds maxQueryChars=" + max);
        }
        return normalized;
    }

    private String normalizeContentId(String contentId) {
        if (!hasText(contentId) || !contentId.trim().matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("contentId must be a safe Confluence content id");
        }
        return contentId.trim();
    }

    private int resolveLimit(Integer requested) {
        int max = Math.max(1, properties.getMaxSearchLimit());
        if (requested == null || requested <= 0) {
            return Math.min(Math.max(1, properties.getDefaultSearchLimit()), max);
        }
        return Math.min(requested, max);
    }

    private void ensureConfigured() {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("Confluence 未配置或未启用");
        }
    }

    private String pageUrl(JsonNode item, JsonNode root) {
        String webUi = text(item.path("_links"), "webui");
        if (webUi.isBlank()) {
            webUi = text(item.path("_links"), "self");
        }
        if (webUi.isBlank()) {
            return "";
        }
        if (webUi.startsWith("http://") || webUi.startsWith("https://")) {
            return webUi;
        }
        String base = text(root.path("_links"), "base");
        if (base.isBlank()) {
            base = properties.getBaseUrl();
        }
        if (base == null || base.isBlank()) {
            return webUi;
        }
        return base.replaceAll("/$", "") + (webUi.startsWith("/") ? webUi : "/" + webUi);
    }

    private static String cleanSnippet(String value) {
        return value == null ? "" : value.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return "";
        }
        return node.path(field).asText("").trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static RestClient buildClient(ConfluenceProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(Math.max(500, properties.getTimeoutMs()));
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
