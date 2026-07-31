package com.ai.itops.rag.service;

import com.ai.itops.rag.dto.MultipartPartInfo;
import com.ai.itops.rag.entity.DocumentMetadata;
import com.ai.itops.rag.mapper.DocumentMetadataMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIngestionServiceTest {

    @Test
    void completeMultipartUploadShouldSkipComposeWhenFinalObjectAlreadyExists() throws Exception {
        RustFsService rustFsService = mock(RustFsService.class);
        DocumentMetadataMapper metadataMapper = mock(DocumentMetadataMapper.class);
        DocumentIngestOutboxService outboxService = mock(DocumentIngestOutboxService.class);
        when(outboxService.hasOutbox("task-1")).thenReturn(false);
        when(outboxService.currentTraceId("task-1")).thenReturn("trace-1");
        when(outboxService.enqueueExistingTask(any(DocumentMetadata.class), eq("trace-1"))).thenReturn(true);
        when(rustFsService.docObjectExists("workspace/default/task-1_demo.pdf")).thenReturn(true);

        KnowledgeIngestionService service = new KnowledgeIngestionService(
                provider(rustFsService),
                metadataMapper,
                outboxService
        );

        service.completeMultipartUpload(row("task-1"), "task-1", "workspace/default/task-1_demo.pdf", List.of());

        verify(rustFsService, never()).composeDocObject(any(), anyString());
        verify(outboxService).enqueueExistingTask(any(DocumentMetadata.class), eq("trace-1"));
    }

    @Test
    void completeMultipartUploadShouldBeIdempotentWhenOutboxAlreadyExists() throws Exception {
        RustFsService rustFsService = mock(RustFsService.class);
        DocumentMetadataMapper metadataMapper = mock(DocumentMetadataMapper.class);
        DocumentIngestOutboxService outboxService = mock(DocumentIngestOutboxService.class);
        when(outboxService.hasOutbox("task-2")).thenReturn(true);

        KnowledgeIngestionService service = new KnowledgeIngestionService(
                provider(rustFsService),
                metadataMapper,
                outboxService
        );

        service.completeMultipartUpload(
                row("task-2"),
                "task-2",
                "workspace/default/task-2_demo.pdf",
                List.of(new MultipartPartInfo(1, "etag-1"))
        );

        verify(rustFsService, never()).docObjectExists(anyString());
        verify(rustFsService, never()).composeDocObject(any(), anyString());
        verify(outboxService, never()).enqueueExistingTask(any(DocumentMetadata.class), anyString());
    }

    private static DocumentMetadata row(String taskId) {
        DocumentMetadata row = new DocumentMetadata();
        row.setTaskId(taskId);
        row.setMinioPath("workspace/default/" + taskId + "_demo.pdf");
        row.setWorkspaceId("default");
        row.setVisibility(DocumentMetadata.VISIBILITY_PRIVATE);
        row.setUserId(7L);
        row.setFileName("demo.pdf");
        row.setFileSize(128L);
        row.setStatus(DocumentMetadata.STATUS_PENDING);
        return row;
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
