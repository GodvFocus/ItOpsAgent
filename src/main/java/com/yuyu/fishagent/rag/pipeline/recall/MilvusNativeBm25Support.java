package com.yuyu.fishagent.rag.pipeline.recall;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.response.SearchResp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Milvus 2.5+ 原生 BM25 Function 查询适配器。 */
public final class MilvusNativeBm25Support {

    private MilvusNativeBm25Support() {
    }

    /** 判断服务端是否具备 raw text + BM25 Function 能力。 */
    public static boolean serverSupportsBm25(String version) {
        if (version == null) {
            return false;
        }
        String normalized = version.trim().toLowerCase();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("v?(\\d+)\\.(\\d+)").matcher(normalized);
        if (!matcher.find()) {
            return false;
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        return major > 2 || (major == 2 && minor >= 5);
    }

    /** 使用原始文本查询稀疏 BM25 向量，并保留过滤表达式。 */
    public static List<NativeHit> search(
            MilvusClientV2 client,
            String collection,
            String sparseField,
            String query,
            String filter,
            int size,
            List<String> outputFields) {
        if (client == null || collection == null || collection.isBlank()
                || query == null || query.isBlank() || size <= 0) {
            return List.of();
        }
        SearchReq request = SearchReq.builder()
                .collectionName(collection)
                .annsField(sparseField)
                .metricType(IndexParam.MetricType.BM25)
                .data(List.of(new EmbeddedText(query.trim())))
                .filter(filter)
                .topK(size)
                .outputFields(outputFields)
                .build();
        SearchResp response = client.search(request);
        if (response == null || response.getSearchResults() == null
                || response.getSearchResults().isEmpty()) {
            return List.of();
        }
        List<SearchResp.SearchResult> results = response.getSearchResults().get(0);
        List<NativeHit> hits = new ArrayList<>();
        if (results == null) {
            return hits;
        }
        for (SearchResp.SearchResult result : results) {
            if (result == null) {
                continue;
            }
            String id = result.getPrimaryKey();
            if (id == null && result.getId() != null) {
                id = String.valueOf(result.getId());
            }
            hits.add(new NativeHit(id, result.getScore() == null ? 0.0 : result.getScore(),
                    result.getEntity() == null ? Map.of() : result.getEntity()));
        }
        return hits;
    }

    public record NativeHit(String id, double score, Map<String, Object> entity) {

        public String string(String field) {
            Object value = entity.get(field);
            return value == null ? null : String.valueOf(value);
        }

        public Double number(String field) {
            Object value = entity.get(field);
            return value instanceof Number number ? number.doubleValue() : null;
        }

        public Long longNumber(String field) {
            Object value = entity.get(field);
            return value instanceof Number number ? number.longValue() : null;
        }
    }
}
