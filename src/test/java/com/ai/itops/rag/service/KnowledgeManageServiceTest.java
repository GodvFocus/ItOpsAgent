package com.ai.itops.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ai.itops.rag.config.MilvusProperties;
import com.ai.itops.rag.entity.DocumentMetadata;
import com.ai.itops.rag.mapper.DocumentMetadataMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.dml.DeleteParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeManageServiceTest {

    @Mock
    DocumentMetadataMapper documentMetadataMapper;
    @Mock
    ObjectProvider<RustFsService> rustFsProvider;
    @Mock
    ChunkClusterService chunkClusterService;
    @Mock
    ObjectProvider<MilvusServiceClient> milvusClientProvider;
    @Mock
    MilvusServiceClient milvusClient;

    KnowledgeManageService service;

    @BeforeEach
    void setUp() {
        MilvusProperties milvusProperties = new MilvusProperties();
        milvusProperties.setUserKnowledgeCollection("user_docs");
        milvusProperties.setPublicKnowledgeCollection("workspace_docs");
        service = new KnowledgeManageService(
                documentMetadataMapper,
                rustFsProvider,
                chunkClusterService,
                milvusClientProvider,
                milvusProperties
        );
    }

    @Test
    void deleteByTaskIdDeletesPrivateDocumentVectorsFromUserCollection() {
        DocumentMetadata row = new DocumentMetadata();
        row.setTaskId("task-private");
        row.setUserId(7L);
        row.setVisibility(DocumentMetadata.VISIBILITY_PRIVATE);
        when(documentMetadataMapper.selectOne(any())).thenReturn(row);
        when(milvusClientProvider.getIfAvailable()).thenReturn(milvusClient);

        service.deleteByTaskId("task-private", 7L, false);

        ArgumentCaptor<DeleteParam> deleteCaptor = ArgumentCaptor.forClass(DeleteParam.class);
        verify(milvusClient).delete(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().getCollectionName()).isEqualTo("user_docs");
        assertThat(deleteCaptor.getValue().getExpr()).isEqualTo("doc_id == \"task-private\"");
        verify(chunkClusterService).evictClusterCache("task-private");
        verify(documentMetadataMapper).delete(any());
    }

    @Test
    void deleteByTaskIdDeletesWorkspaceDocumentVectorsFromPublicCollection() {
        DocumentMetadata row = new DocumentMetadata();
        row.setTaskId("task-workspace");
        row.setUserId(9L);
        row.setVisibility(DocumentMetadata.VISIBILITY_WORKSPACE);
        when(documentMetadataMapper.selectOne(any())).thenReturn(row);
        when(milvusClientProvider.getIfAvailable()).thenReturn(milvusClient);

        service.deleteByTaskId("task-workspace", 9L, false);

        ArgumentCaptor<DeleteParam> deleteCaptor = ArgumentCaptor.forClass(DeleteParam.class);
        verify(milvusClient).delete(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().getCollectionName()).isEqualTo("workspace_docs");
        assertThat(deleteCaptor.getValue().getExpr()).isEqualTo("doc_id == \"task-workspace\"");
    }

    @Test
    void deleteByTaskIdFallsBackToDeletingBothCollectionsWhenVisibilityIsDirty() {
        DocumentMetadata row = new DocumentMetadata();
        row.setTaskId("task-legacy");
        row.setUserId(5L);
        row.setVisibility("UNKNOWN");
        when(documentMetadataMapper.selectOne(any())).thenReturn(row);
        when(milvusClientProvider.getIfAvailable()).thenReturn(milvusClient);

        service.deleteByTaskId("task-legacy", 5L, false);

        ArgumentCaptor<DeleteParam> deleteCaptor = ArgumentCaptor.forClass(DeleteParam.class);
        verify(milvusClient, org.mockito.Mockito.times(2)).delete(deleteCaptor.capture());
        assertThat(deleteCaptor.getAllValues())
                .extracting(DeleteParam::getCollectionName)
                .containsExactly("user_docs", "workspace_docs");
    }
}
