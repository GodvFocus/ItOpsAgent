package com.ai.itops.common.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.itops.rag.pipeline.recall.RagRecall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceRegistryTest {

    private final EvidenceRegistry registry = new EvidenceRegistry(new ObjectMapper());

    @Test
    void shouldRegisterRagAndToolRecordsInOneImmutableTurnRegistry() throws Exception {
        registry.startTurn("turn-1");
        registry.registerRagHits("turn-1", List.of(
                new RagRecall.RecallHit("doc-1", "订单服务重启 SOP", 0.9, RagRecall.RecallSource.TEXT)));

        String enriched = registry.registerToolResult("turn-1", "log_search_tool", """
                {"ok":true,"hits":[{"id":"log-1","serviceName":"order","message":"ECONNRESET"}]}
                """);
        JsonNode root = new ObjectMapper().readTree(enriched);

        assertThat(root.path("hits").get(0).path("evidenceId").asText()).isEqualTo("E2");
        assertThat(registry.snapshot("turn-1")).extracting(Evidence::evidenceId)
                .containsExactly("E1", "E2");
        assertThat(registry.find("turn-1", "E2").type()).isEqualTo(EvidenceType.LOG);
        assertThat(registry.find("turn-1", "E2").metadata()).containsEntry("tool", "log_search_tool");
    }

    @Test
    void shouldIsolateIdsByTurnAndCopyMetadata() {
        Evidence evidence = registry.register("turn-a", EvidenceType.TICKET, "ticket-1", "工单", "记录",
                Map.of("owner", "ops"));
        registry.register("turn-b", EvidenceType.STATUS_SNAPSHOT, "order", "order", "healthy");

        assertThat(evidence.evidenceId()).isEqualTo("E1");
        assertThat(registry.snapshot("turn-a")).hasSize(1);
        assertThat(registry.snapshot("turn-b")).extracting(Evidence::evidenceId).containsExactly("E1");
        assertThat(evidence.metadata()).containsEntry("owner", "ops");
        assertThatThrownBy(() -> evidence.metadata().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
