package com.ai.itops.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TroubleshootingEvalRunnerTest {

    @Test
    void runnerShouldPassMinimalTroubleshootingBenchmark() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TroubleshootingGoldenSet goldenSet;
        try (InputStream input = getClass().getResourceAsStream("/eval/golden-troubleshooting.json")) {
            TroubleshootingGoldenSet.Case[] cases = objectMapper.readValue(input, TroubleshootingGoldenSet.Case[].class);
            goldenSet = new TroubleshootingGoldenSet(List.of(cases));
        }

        TroubleshootingEvalReport report = new TroubleshootingEvalRunner().run(goldenSet, 3);

        assertThat(report.caseCount()).isEqualTo(4);
        assertThat(report.routeAccuracy()).isEqualTo(1.0);
        assertThat(report.routeF1()).isEqualTo(1.0);
        assertThat(report.toolSelectionAccuracy()).isEqualTo(1.0);
        assertThat(report.toolParameterAccuracy()).isEqualTo(1.0);
        assertThat(report.unauthorizedRecallCount()).isEqualTo(0);
        assertThat(report.recallAtK()).isGreaterThanOrEqualTo(0.75);
        assertThat(report.exactTokenHitRate()).isGreaterThanOrEqualTo(0.75);
        assertThat(report.citationAccuracy()).isGreaterThanOrEqualTo(0.75);
        assertThat(report.citationCoverage()).isGreaterThanOrEqualTo(0.75);
    }
}
