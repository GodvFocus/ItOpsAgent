package com.ai.itops.agent.tool.confluence;

import java.util.List;

/**
 * Confluence 连接器抽象，隔离 REST API、认证和返回结构适配。
 */
public interface ConfluenceClient {

    SearchResponse search(String query, String spaceKey, Integer limit);

    DocumentResponse fetch(String contentId);

    record SearchResponse(boolean ok, String message, List<SearchHit> hits) {
        public SearchResponse {
            message = message == null ? "" : message;
            hits = hits == null ? List.of() : List.copyOf(hits);
        }

        public static SearchResponse empty(String message) {
            return new SearchResponse(false, message, List.of());
        }
    }

    record SearchHit(String id, String title, String spaceName, String snippet, String url) {
    }

    record DocumentResponse(boolean ok, String message, List<Document> records) {
        public DocumentResponse {
            message = message == null ? "" : message;
            records = records == null ? List.of() : List.copyOf(records);
        }

        public static DocumentResponse empty(String message) {
            return new DocumentResponse(false, message, List.of());
        }
    }

    record Document(String id, String title, String spaceName, String url, String content) {
    }
}
