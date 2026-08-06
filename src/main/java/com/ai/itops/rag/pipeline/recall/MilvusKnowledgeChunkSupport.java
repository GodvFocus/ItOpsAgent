package com.ai.itops.rag.pipeline.recall;

import com.ai.itops.rag.config.MilvusProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.MetricType;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档切片的 Milvus 查询适配层。
 *
 * <p>切片查询必须同时带上文档、workspace、用户和 ready 条件，避免邻块扩展、可视化接口
 * 或反向关联越过现有的检索隔离边界。BM25 collection 与兼容 collection 的选择也统一收敛在这里。</p>
 */
public final class MilvusKnowledgeChunkSupport {

    private static final List<String> CHUNK_FIELDS = List.of(
            "id", "content", "embedding", "doc_id", "chunk_index", "doc_name", "authority", "created_at");

    private MilvusKnowledgeChunkSupport() {
    }

    public static List<ChunkRow> scanDocument(
            MilvusServiceClient client,
            io.milvus.v2.client.MilvusClientV2 clientV2,
            MilvusProperties properties,
            String docId,
            String workspaceId,
            Long userId,
            boolean publicScope,
            Integer fromChunk,
            Integer toChunk,
            int maxRows,
            int batchSize) {
        if (client == null || properties == null || blank(docId) || blank(workspaceId)
                || (!publicScope && userId == null) || maxRows <= 0) {
            return List.of();
        }
        String expression = documentExpression(properties, clientV2, docId, workspaceId, userId,
                publicScope, fromChunk, toChunk);
        List<QueryResultsWrapper.RowRecord> rows = MilvusLexicalSearchSupport.scan(
                client,
                collection(properties, clientV2, publicScope),
                expression,
                CHUNK_FIELDS,
                maxRows,
                batchSize);
        return rows.stream().map(MilvusKnowledgeChunkSupport::fromRow).filter(row -> row != null).toList();
    }

    public static List<ScoredChunkRow> searchByVector(
            MilvusServiceClient client,
            io.milvus.v2.client.MilvusClientV2 clientV2,
            MilvusProperties properties,
            List<Float> vector,
            String docId,
            String workspaceId,
            Long userId,
            boolean publicScope,
            int topK,
            int nprobe) {
        if (client == null || properties == null || vector == null || vector.isEmpty()
                || blank(docId) || blank(workspaceId) || (!publicScope && userId == null)) {
            return List.of();
        }
        try {
            SearchParam search = SearchParam.newBuilder()
                    .withCollectionName(collection(properties, clientV2, publicScope))
                    .withVectors(List.of(vector))
                    .withVectorFieldName("embedding")
                    .withTopK(Math.max(1, topK))
                    .withMetricType(MetricType.COSINE)
                    .withExpr(documentExpression(properties, clientV2, docId, workspaceId, userId,
                            publicScope, null, null))
                    .withParams("{\"nprobe\":" + Math.max(1, nprobe) + "}")
                    .withOutFields(CHUNK_FIELDS)
                    .build();
            SearchResultsWrapper results = new SearchResultsWrapper(client.search(search).getData().getResults());
            List<SearchResultsWrapper.IDScore> scores = results.getIDScore(0);
            List<QueryResultsWrapper.RowRecord> rows = results.getRowRecords();
            if (scores == null || rows == null) {
                return List.of();
            }
            int count = Math.min(scores.size(), rows.size());
            List<ScoredChunkRow> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                ChunkRow row = fromRow(rows.get(i));
                if (row != null) {
                    out.add(new ScoredChunkRow(row, scores.get(i).getScore()));
                }
            }
            return out;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public static String collection(MilvusProperties properties,
                                    io.milvus.v2.client.MilvusClientV2 clientV2,
                                    boolean publicScope) {
        if (properties.getBm25().isEnabled() && clientV2 != null) {
            try {
                if (MilvusNativeBm25Support.serverSupportsBm25(clientV2.getServerVersion())) {
                    return publicScope
                            ? properties.getBm25().getPublicKnowledgeCollection()
                            : properties.getBm25().getUserKnowledgeCollection();
                }
            } catch (Exception ignored) {
                // 服务版本不可用时回退到兼容 collection，避免可视化接口被版本探测阻断。
            }
        }
        return publicScope ? properties.getPublicKnowledgeCollection() : properties.getUserKnowledgeCollection();
    }

    public static String documentExpression(MilvusProperties properties,
                                             io.milvus.v2.client.MilvusClientV2 clientV2,
                                             String docId,
                                             String workspaceId,
                                             Long userId,
                                             boolean publicScope,
                                             Integer fromChunk,
                                             Integer toChunk) {
        StringBuilder expression = new StringBuilder("doc_id == \"")
                .append(escape(docId)).append("\" and workspace_id == \"")
                .append(escape(workspaceId)).append("\" and ready == true");
        if (!publicScope && userId != null) {
            expression.append(" and user_id == \"").append(userId).append("\"");
        }
        if (fromChunk != null) {
            expression.append(" and chunk_index >= ").append(Math.max(0, fromChunk));
        }
        if (toChunk != null) {
            expression.append(" and chunk_index <= ").append(Math.max(0, toChunk));
        }
        return expression.toString();
    }

    public static ChunkRow fromRow(QueryResultsWrapper.RowRecord row) {
        if (row == null) {
            return null;
        }
        String content = string(row.get("content"));
        Integer chunkIndex = integer(row.get("chunk_index"));
        if (blank(content) || chunkIndex == null || chunkIndex < 0) {
            return null;
        }
        return new ChunkRow(
                string(row.get("id")),
                content.trim(),
                chunkIndex,
                vector(row.get("embedding")),
                string(row.get("doc_id")),
                string(row.get("doc_name")),
                number(row.get("authority")),
                longNumber(row.get("created_at")));
    }

    public static double cosine(List<Float> left, List<Float> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        int size = Math.min(left.size(), right.size());
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < size; i++) {
            double l = left.get(i) == null ? 0.0 : left.get(i);
            double r = right.get(i) == null ? 0.0 : right.get(i);
            dot += l * r;
            leftNorm += l * l;
            rightNorm += r * r;
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / Math.sqrt(leftNorm * rightNorm);
    }

    private static List<Float> vector(Object value) {
        if (value instanceof List<?> values) {
            List<Float> out = new ArrayList<>(values.size());
            for (Object item : values) {
                if (item instanceof Number number) {
                    out.add(number.floatValue());
                }
            }
            return out;
        }
        if (value instanceof float[] values) {
            List<Float> out = new ArrayList<>(values.length);
            for (float item : values) {
                out.add(item);
            }
            return out;
        }
        return List.of();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Long longNumber(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record ChunkRow(String id, String content, Integer chunkIndex, List<Float> embedding,
                           String docId, String docName, Double authority, Long createdAt) {
    }

    public record ScoredChunkRow(ChunkRow row, double score) {
    }
}
