package com.broker.tools;

import com.broker.exception.BreezeApiException;
import com.broker.exception.BrokerCapabilityException;
import com.broker.model.AnalysisModels.*;
import com.broker.service.BrokerDataProvider;
import com.broker.service.CompoundToolService;
import com.broker.service.CompoundToolServiceTestFactory;
import com.broker.service.StockMetadataService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.context.MetaProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompoundIntelligenceToolsTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void stockCheckupToolDescription_shouldAdvertiseQuoteRequests() throws Exception {
        Method method = CompoundIntelligenceTools.class.getMethod("stockCheckup", String.class, String.class);
        McpTool annotation = method.getAnnotation(McpTool.class);

        assertTrue(annotation.description().contains("quote"));
        assertTrue(annotation.description().contains("current price"));
    }

    @Test
    void portfolioAndTaxTools_shouldExposeMcpAppResourceUris() throws Exception {
        assertToolResourceUri(
                CompoundIntelligenceTools.class.getMethod("portfolioSnapshot"),
                "ui://portfolio/portfolio-dashboard.html"
        );
        assertToolResourceUri(
                CompoundIntelligenceTools.class.getMethod("taxHarvestReport"),
                "ui://tax/tax-harvest-report.html"
        );
    }

    @Test
    void portfolioAndTaxAppResources_shouldUseExtAppsSdkAndConnect() throws Exception {
        assertUsesExtAppsSdk("mcp-apps/portfolio-dashboard.html");
        assertUsesExtAppsSdk("mcp-apps/tax-harvest-report.html");
    }

    @Test
    void taxHarvestAppResource_shouldReadCurrentServiceFieldNames() throws Exception {
        String html = new ClassPathResource("mcp-apps/tax-harvest-report.html")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(html.contains("classification"));
        assertTrue(html.contains("holding_months"));
        assertTrue(html.contains("loss_amount"));
        assertTrue(html.contains("gain_or_loss"));
    }

    @Test
    void taxHarvestAppResource_shouldHandleObjectCoverageSources() throws Exception {
        String html = new ClassPathResource("mcp-apps/tax-harvest-report.html")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(html.contains("const coverageSourcesOf = (rawSources) =>"));
        assertTrue(html.contains("Object.entries(rawSources)"));
        assertTrue(html.contains("coverageSourcesOf(data.data_sources)"));
    }

    @Test
    void taxHarvestAppResource_shouldShowPlainTextPreviewErrorsWithoutJsonParseFailure() throws Exception {
        String html = new ClassPathResource("mcp-apps/tax-harvest-report.html")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(html.contains("const extractToolText = (result) =>"));
        assertTrue(html.contains("try {"));
        assertTrue(html.contains("return textEntry ? JSON.parse(textEntry) : null;"));
        assertTrue(html.contains("return null;"));
        assertTrue(html.contains("const toolText = extractToolText(result);"));
        assertTrue(html.contains("toolText || 'Preview tool returned no payload.'"));
    }

    @Test
    void taxHarvestAppResource_shouldPassCandidateBrokerIntoPreviewTool() throws Exception {
        String html = new ClassPathResource("mcp-apps/tax-harvest-report.html")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(html.contains("broker: entry.broker"));
    }

    @Test
    void taxHarvestAppResource_shouldScrollAndHighlightSellPreviewOnCandidateClick() throws Exception {
        String html = new ClassPathResource("mcp-apps/tax-harvest-report.html")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(html.contains("previewPanel: document.getElementById('previewPanel')"));
        assertTrue(html.contains("function focusPreviewPanel()"));
        assertTrue(html.contains("elements.previewPanel.scrollIntoView("));
        assertTrue(html.contains("elements.previewPanel.classList.add('preview-focus')"));
    }

    @Test
    void orderPreviewTool_shouldAcceptOptionalBrokerForRoutingSensitivePreview() throws Exception {
        Method method = CompoundIntelligenceTools.class.getMethod(
                "orderPreview",
                String.class,
                String.class,
                int.class,
                String.class,
                Double.class,
                String.class,
                String.class
        );

        assertTrue(method.getParameters()[6].getAnnotation(org.springframework.ai.mcp.annotation.McpToolParam.class)
                .description().toLowerCase().contains("broker"));
    }

    @Test
    void marketPulse_shouldReturnStructuredUnavailableForFreeTierCapabilityError() throws Exception {
        CompoundIntelligenceTools tools = new CompoundIntelligenceTools(
                new ThrowingCompoundToolService(new BrokerCapabilityException("Zerodha live quotes require zerodha.tier=paid")),
                objectMapper
        );

        Map<String, Object> result = objectMapper.readValue(tools.marketPulse(), Map.class);

        assertEquals("UNAVAILABLE", result.get("status"));
        assertEquals("market_pulse", result.get("tool"));
        assertEquals("Zerodha live quotes require zerodha.tier=paid", result.get("message"));
        assertEquals("Use another configured broker for market data or enable the required Zerodha paid tier.", result.get("recommendation"));
    }

    @Test
    void stockCheckup_shouldReturnStructuredUnavailableForSessionIssue() throws Exception {
        CompoundIntelligenceTools tools = new CompoundIntelligenceTools(
                new ThrowingCompoundToolService(new BreezeApiException("Zerodha session expired", 401)),
                objectMapper
        );

        Map<String, Object> result = objectMapper.readValue(tools.stockCheckup("TATPOW", "NSE"), Map.class);

        assertEquals("UNAVAILABLE", result.get("status"));
        assertEquals("stock_checkup", result.get("tool"));
        assertEquals("Zerodha session expired", result.get("message"));
        assertEquals("Refresh the active broker session and retry.", result.get("recommendation"));
    }

    @Test
    void portfolioSnapshot_shouldReturnStructuredUnavailableForExpiredSession() throws Exception {
        CompoundIntelligenceTools tools = new CompoundIntelligenceTools(
                new ThrowingCompoundToolService(new BreezeApiException("Zerodha session expired", 401)),
                objectMapper
        );

        Map<String, Object> result = objectMapper.readValue(tools.portfolioSnapshot(), Map.class);

        assertEquals("UNAVAILABLE", result.get("status"));
        assertEquals("portfolio_snapshot", result.get("tool"));
        assertEquals("Zerodha session expired", result.get("message"));
    }

    private void assertUsesExtAppsSdk(String classpathLocation) throws IOException {
        String html = new ClassPathResource(classpathLocation).getContentAsString(StandardCharsets.UTF_8);

        assertTrue(html.contains("@modelcontextprotocol/ext-apps"));
        assertTrue(html.contains("new App("));
        assertTrue(html.contains("app.connect()"));
    }

    @SuppressWarnings("unchecked")
    private void assertToolResourceUri(Method method, String expectedResourceUri) throws Exception {
        McpTool annotation = method.getAnnotation(McpTool.class);
        MetaProvider provider = annotation.metaProvider().getDeclaredConstructor().newInstance();
        Map<String, Object> meta = provider.getMeta();
        Map<String, Object> ui = (Map<String, Object>) meta.get("ui");

        assertEquals(expectedResourceUri, ui.get("resourceUri"));
    }

    private final class ThrowingCompoundToolService extends CompoundToolService {

        private final RuntimeException error;

        private ThrowingCompoundToolService(RuntimeException error) {
            super(
                    CompoundToolServiceTestFactory.builder(
                                    new NoopBrokerDataProvider(),
                                    new StockMetadataService(new DefaultResourceLoader(), objectMapper, "classpath:stock-universe.csv"),
                                    objectMapper
                            )
                            .dependencies()
            );
            this.error = error;
        }

        @Override
        public String portfolioSnapshot() {
            throw error;
        }

        @Override
        public String marketPulse() {
            throw error;
        }

        @Override
        public String stockCheckup(String stockCode, String exchange) {
            throw error;
        }
    }

    private static final class NoopBrokerDataProvider implements BrokerDataProvider {

        @Override
        public List<HoldingSnapshot> getPortfolioHoldings() {
            return List.of();
        }

        @Override
        public FundsSnapshot getFunds() {
            return new FundsSnapshot(0, 0, "", "stub", Map.of());
        }

        @Override
        public QuoteSnapshot getQuote(String stockCode, String exchangeCode, String productType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, QuoteSnapshot> getQuotes(List<String> stockCodes, String exchangeCode, String productType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<HistoricalCandle> getHistoricalCharts(String stockCode, String exchangeCode, String productType, String interval, LocalDate fromDate, LocalDate toDate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TradeSnapshot> getTrades(LocalDate fromDate, LocalDate toDate) {
            return List.of();
        }

        @Override
        public List<GttOrderSnapshot> getGttOrders() {
            return List.of();
        }

        @Override
        public List<OptionChainSnapshot> getOptionChain(String stockCode, String expiryDate, String right) {
            return List.of();
        }

        @Override
        public tools.jackson.databind.JsonNode previewOrder(Map<String, String> body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public tools.jackson.databind.JsonNode placeOrder(Map<String, String> body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public tools.jackson.databind.JsonNode getOrderDetail(String exchangeCode, String orderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public tools.jackson.databind.JsonNode placeGttOrder(Map<String, String> body) {
            throw new UnsupportedOperationException();
        }
    }
}
