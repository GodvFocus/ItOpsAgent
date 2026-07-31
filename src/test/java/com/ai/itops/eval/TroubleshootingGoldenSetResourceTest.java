package com.ai.itops.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class TroubleshootingGoldenSetResourceTest {

    @Test
    void troubleshootingGoldenSetShouldCoverFourCoreCategories() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream input = getClass().getResourceAsStream("/eval/golden-troubleshooting.json")) {
            TroubleshootingGoldenSet.Case[] cases = objectMapper.readValue(input, TroubleshootingGoldenSet.Case[].class);

            assertThat(cases).hasSize(4);
            assertThat(cases)
                    .extracting(TroubleshootingGoldenSet.Case::category)
                    .containsExactlyInAnyOrder("SOP_QA", "ERROR_RECALL", "STATUS_LOG_CORRELATION", "AUTH_GUARD");
        }
    }
}
