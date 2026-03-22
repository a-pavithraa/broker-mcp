package com.broker.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class McpAppTemplateStructureTest {

    @Test
    void taxHarvestReportIncludesCompactCandidateControls() throws IOException {
        String html = readResource("mcp-apps/tax-harvest-report.html");

        assertThat(html).contains("candidate-table");
        assertThat(html).contains("candidate-table-head");
        assertThat(html).contains("candidate-row");
        assertThat(html).contains("candidate-stock");
        assertThat(html).contains("candidate-details");
        assertThat(html).contains("candidate-table-columns");
        assertThat(html).contains("grid-template-columns: minmax(0, 1.55fr) minmax(110px, 0.7fr) minmax(110px, 0.7fr) minmax(90px, 0.5fr) auto;");
        assertThat(html).contains("@media (max-width: 920px)");
    }

    @Test
    void portfolioDashboardIncludesAllocationBreakdownPanel() throws IOException {
        String html = readResource("mcp-apps/portfolio-dashboard.html");

        assertThat(html).contains("allocation-layout");
        assertThat(html).contains("allocationBreakdown");
        assertThat(html).contains("allocation-center");
        assertThat(html).contains("Top allocation mix");
        assertThat(html).contains("height: auto;");
        assertThat(html).contains("align-items: start;");
    }

    private String readResource(String path) throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as("resource " + path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
