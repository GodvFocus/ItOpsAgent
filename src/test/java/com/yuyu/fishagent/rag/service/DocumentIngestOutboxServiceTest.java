package com.yuyu.fishagent.rag.service;

import com.yuyu.fishagent.rag.config.KnowledgeProperties;
import com.yuyu.fishagent.rag.entity.DocumentIngestOutbox;
import com.yuyu.fishagent.rag.entity.DocumentMetadata;
import com.yuyu.fishagent.rag.mapper.DocumentIngestOutboxMapper;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIngestOutboxServiceTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void createPendingTaskWithOutboxShouldFallbackTraceIdToTaskId() {
        DocumentMetadataMapper metadataMapper = mock(DocumentMetadataMapper.class);
        DocumentIngestOutboxMapper outboxMapper = mock(DocumentIngestOutboxMapper.class);
        DocumentIngestOutboxService service = new DocumentIngestOutboxService(
                metadataMapper,
                outboxMapper,
                provider(null),
                properties()
        );

        DocumentMetadata row = metadata("task-1");
        service.createPendingTaskWithOutbox(row, "   ");

        ArgumentCaptor<DocumentIngestOutbox> captor = ArgumentCaptor.forClass(DocumentIngestOutbox.class);
        verify(outboxMapper).insert(captor.capture());
        assertThat(captor.getValue().getTaskId()).isEqualTo("task-1");
        assertThat(captor.getValue().getTraceId()).isEqualTo("task-1");
        assertThat(captor.getValue().getStatus()).isEqualTo(DocumentIngestOutbox.STATUS_PENDING);
        assertThat(captor.getValue().getNextRetryAt()).isNotNull();
    }

    @Test
    void publishShouldWriteExpectedStreamPayload() {
        DocumentMetadataMapper metadataMapper = mock(DocumentMetadataMapper.class);
        DocumentIngestOutboxMapper outboxMapper = mock(DocumentIngestOutboxMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.add(any(MapRecord.class))).thenReturn(RecordId.of("1-0"));

        DocumentIngestOutboxService service = new DocumentIngestOutboxService(
                metadataMapper,
                outboxMapper,
                provider(redisTemplate),
                properties()
        );

        RecordId recordId = service.publish(outbox("task-2", "trace-java-python-2"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MapRecord<String, String, String>> captor = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOperations).add(captor.capture());
        assertThat(recordId.getValue()).isEqualTo("1-0");
        assertThat(captor.getValue().getStream()).isEqualTo("fish:doc:ingest:test");
        assertThat(captor.getValue().getValue())
                .containsEntry("task_id", "task-2")
                .containsEntry("trace_id", "trace-java-python-2")
                .containsEntry("visibility", "PRIVATE");
    }

    @Test
    void requestReplayShouldResetPublishedOutboxForDuplicateDelivery() {
        DocumentMetadataMapper metadataMapper = mock(DocumentMetadataMapper.class);
        DocumentIngestOutboxMapper outboxMapper = mock(DocumentIngestOutboxMapper.class);
        when(outboxMapper.selectByTaskId("task-3")).thenReturn(outbox("task-3", "trace-3"));

        DocumentIngestOutboxService service = new DocumentIngestOutboxService(
                metadataMapper,
                outboxMapper,
                provider(null),
                properties()
        );

        service.requestReplay("task-3");

        verify(outboxMapper).resetForReplay(eq("task-3"), any(LocalDateTime.class));
        verify(metadataMapper).update(eq(null), any());
    }

    private static DocumentMetadata metadata(String taskId) {
        DocumentMetadata row = new DocumentMetadata();
        row.setTaskId(taskId);
        row.setWorkspaceId("default");
        row.setVisibility(DocumentMetadata.VISIBILITY_PRIVATE);
        row.setUserId(7L);
        row.setFileName("demo.pdf");
        row.setFileSize(128L);
        row.setMinioPath("workspace/default/" + taskId + "_demo.pdf");
        row.setStatus(DocumentMetadata.STATUS_PENDING);
        return row;
    }

    private static DocumentIngestOutbox outbox(String taskId, String traceId) {
        DocumentIngestOutbox row = new DocumentIngestOutbox();
        row.setId(1L);
        row.setTaskId(taskId);
        row.setStreamKey("fish:doc:ingest:test");
        row.setMinioPath("workspace/default/" + taskId + "_demo.pdf");
        row.setWorkspaceId("default");
        row.setVisibility(DocumentMetadata.VISIBILITY_PRIVATE);
        row.setUserId(7L);
        row.setFileName("demo.pdf");
        row.setFileSize(128L);
        row.setTraceId(traceId);
        row.setStatus(DocumentIngestOutbox.STATUS_PUBLISHED);
        row.setAttemptCount(1);
        return row;
    }

    private static KnowledgeProperties properties() {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.setDocumentIngestStreamKey("fish:doc:ingest:test");
        return properties;
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
