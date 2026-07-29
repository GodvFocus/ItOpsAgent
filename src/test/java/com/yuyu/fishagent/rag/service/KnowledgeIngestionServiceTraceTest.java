package com.yuyu.fishagent.rag.service;

import com.yuyu.fishagent.common.trace.TraceConstants;
import com.yuyu.fishagent.rag.config.KnowledgeProperties;
import com.yuyu.fishagent.rag.mapper.DocumentMetadataMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeIngestionServiceTraceTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldPublishRequestTraceIdIntoRedisStreamPayload() {
        RustFsService rustFsService = mock(RustFsService.class);
        DocumentMetadataMapper mapper = mock(DocumentMetadataMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        ObjectProvider<RustFsService> rustFsProvider = provider(rustFsService);
        ObjectProvider<StringRedisTemplate> redisProvider = provider(redisTemplate);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.setDocumentIngestStreamKey("fish:doc:ingest:test");

        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.add(any(MapRecord.class))).thenReturn(RecordId.of("1-0"));

        KnowledgeIngestionService service = new KnowledgeIngestionService(
                rustFsProvider,
                mapper,
                redisProvider,
                properties
        );
        MDC.put(TraceConstants.TRACE_ID, "trace-java-python-1");

        ReflectionTestUtils.invokeMethod(
                service,
                "publishStream",
                "task-1",
                "workspace/default/task-1_demo.pdf",
                "default",
                "PRIVATE",
                7L,
                "demo.pdf",
                128L
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MapRecord<String, String, String>> recordCaptor = ArgumentCaptor.forClass(MapRecord.class);
        org.mockito.Mockito.verify(streamOperations).add(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getValue())
                .containsEntry("trace_id", "trace-java-python-1")
                .containsEntry("task_id", "task-1");
    }

    @Test
    void shouldFallbackToTaskIdWhenNoRequestTraceExists() {
        RustFsService rustFsService = mock(RustFsService.class);
        DocumentMetadataMapper mapper = mock(DocumentMetadataMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        ObjectProvider<RustFsService> rustFsProvider = provider(rustFsService);
        ObjectProvider<StringRedisTemplate> redisProvider = provider(redisTemplate);
        KnowledgeProperties properties = new KnowledgeProperties();

        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.add(any(MapRecord.class))).thenReturn(RecordId.of("1-1"));

        KnowledgeIngestionService service = new KnowledgeIngestionService(
                rustFsProvider,
                mapper,
                redisProvider,
                properties
        );

        ReflectionTestUtils.invokeMethod(
                service,
                "publishStream",
                "task-fallback",
                "workspace/default/task-fallback_demo.pdf",
                "default",
                "PRIVATE",
                7L,
                "demo.pdf",
                128L
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MapRecord<String, String, String>> recordCaptor = ArgumentCaptor.forClass(MapRecord.class);
        org.mockito.Mockito.verify(streamOperations).add(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getValue().get("trace_id")).isEqualTo("task-fallback");
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }
}
