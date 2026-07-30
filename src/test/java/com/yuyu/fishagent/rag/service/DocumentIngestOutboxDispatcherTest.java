package com.yuyu.fishagent.rag.service;

import com.yuyu.fishagent.rag.config.KnowledgeProperties;
import com.yuyu.fishagent.rag.entity.DocumentIngestOutbox;
import com.yuyu.fishagent.rag.mapper.DocumentIngestOutboxMapper;
import com.yuyu.fishagent.rag.mapper.DocumentMetadataMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.RecordId;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIngestOutboxDispatcherTest {

    @Test
    void dispatchOneShouldMarkPublishedOnSuccess() {
        DocumentIngestOutboxMapper outboxMapper = mock(DocumentIngestOutboxMapper.class);
        DocumentMetadataMapper metadataMapper = mock(DocumentMetadataMapper.class);
        DocumentIngestOutboxService outboxService = mock(DocumentIngestOutboxService.class);
        when(outboxMapper.claimForDispatch(eq(1L), eq(0), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        when(outboxService.publish(any(DocumentIngestOutbox.class))).thenReturn(RecordId.of("1-0"));

        DocumentIngestOutboxDispatcher dispatcher = new DocumentIngestOutboxDispatcher(
                outboxMapper,
                metadataMapper,
                outboxService,
                properties()
        );

        dispatcher.dispatchOne(outbox(1L, 0), LocalDateTime.now().minusSeconds(60));

        verify(outboxMapper).markPublished(eq(1L), eq("1-0"), any(LocalDateTime.class));
    }

    @Test
    void dispatchOneShouldScheduleRetryWithExponentialBackoff() {
        DocumentIngestOutboxMapper outboxMapper = mock(DocumentIngestOutboxMapper.class);
        DocumentMetadataMapper metadataMapper = mock(DocumentMetadataMapper.class);
        DocumentIngestOutboxService outboxService = mock(DocumentIngestOutboxService.class);
        when(outboxMapper.claimForDispatch(eq(2L), eq(1), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        when(outboxService.publish(any(DocumentIngestOutbox.class))).thenThrow(new IllegalStateException("redis down"));

        DocumentIngestOutboxDispatcher dispatcher = new DocumentIngestOutboxDispatcher(
                outboxMapper,
                metadataMapper,
                outboxService,
                properties()
        );

        dispatcher.dispatchOne(outbox(2L, 1), LocalDateTime.now().minusSeconds(60));

        ArgumentCaptor<LocalDateTime> nextRetryCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> updatedAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(outboxMapper).markRetry(eq(2L), nextRetryCaptor.capture(), anyString(), updatedAtCaptor.capture());
        assertThat(Duration.between(updatedAtCaptor.getValue(), nextRetryCaptor.getValue()).getSeconds()).isEqualTo(10);
    }

    @Test
    void dispatchOneShouldMoveEventToDlqAfterMaxAttempts() {
        DocumentIngestOutboxMapper outboxMapper = mock(DocumentIngestOutboxMapper.class);
        DocumentMetadataMapper metadataMapper = mock(DocumentMetadataMapper.class);
        DocumentIngestOutboxService outboxService = mock(DocumentIngestOutboxService.class);
        KnowledgeProperties properties = properties();
        properties.getOutbox().setMaxAttempts(3);
        when(outboxMapper.claimForDispatch(eq(3L), eq(2), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        when(outboxService.publish(any(DocumentIngestOutbox.class))).thenThrow(new IllegalStateException("redis down"));

        DocumentIngestOutboxDispatcher dispatcher = new DocumentIngestOutboxDispatcher(
                outboxMapper,
                metadataMapper,
                outboxService,
                properties
        );

        dispatcher.dispatchOne(outbox(3L, 2), LocalDateTime.now().minusSeconds(60));

        verify(outboxMapper).markDead(eq(3L), anyString(), any(LocalDateTime.class));
        verify(metadataMapper).update(eq(null), any());
    }

    private static DocumentIngestOutbox outbox(Long id, int attemptCount) {
        DocumentIngestOutbox row = new DocumentIngestOutbox();
        row.setId(id);
        row.setTaskId("task-" + id);
        row.setAttemptCount(attemptCount);
        row.setStreamKey("fish:doc:ingest:test");
        row.setStatus(DocumentIngestOutbox.STATUS_PENDING);
        return row;
    }

    private static KnowledgeProperties properties() {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getOutbox().setRetryBaseSeconds(5);
        properties.getOutbox().setRetryMaxSeconds(300);
        properties.getOutbox().setMaxAttempts(8);
        return properties;
    }
}
