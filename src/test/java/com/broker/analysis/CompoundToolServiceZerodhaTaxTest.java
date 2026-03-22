package com.broker.analysis;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.broker.config.ZerodhaTradebookProperties;
import com.broker.gateway.BrokerDataProvider;
import com.broker.gateway.zerodha.ZerodhaTradebookService;
import com.broker.model.AnalysisModels.*;
import com.broker.reference.StockMetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompoundToolServiceZerodhaTaxTest {

    private ObjectMapper objectMapper;
    private StockMetadataService stockMetadataService;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        stockMetadataService = new StockMetadataService(new DefaultResourceLoader(), objectMapper, "classpath:stock-universe.csv");
    }

    @Test
    void taxHarvestReportShouldReturnPartialStatusWhenZerodhaTradebookIsMissing(@TempDir Path tempDir) throws Exception {
        BrokerDataProvider provider = new MixedBrokerDataProvider();
        ZerodhaTradebookService tradebookService = new ZerodhaTradebookService(
                new ZerodhaTradebookProperties(
                        Files.createDirectories(tempDir.resolve("imports")).toString(),
                        tempDir.resolve("data").resolve("zerodha-tradebook.json").toString()
                ),
                stockMetadataService,
                objectMapper,
                fixedIndiaClock("2026-03-16T11:00:00+05:30")
        );
        CompoundToolService service = service(provider, fixedIndiaClock("2026-03-16T11:00:00+05:30"), tradebookService);

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);
        List<String> fullyCovered = (List<String>) result.get("brokers_fully_covered");
        List<String> partial = (List<String>) result.get("brokers_partial");
        List<Map<String, Object>> estimated = (List<Map<String, Object>>) result.get("estimated_from_holdings_avg");

        assertEquals("NEEDS_ZERODHA_TRADEBOOK", result.get("status"));
        assertTrue(String.valueOf(result.get("message")).contains("same-day trades"));
        assertEquals(List.of("icici"), fullyCovered);
        assertEquals(List.of("zerodha"), partial);
        Map<String, Object> infy = estimated.stream()
                .filter(item -> "INFY".equals(item.get("stock")))
                .findFirst()
                .orElseThrow();
        assertEquals(10.0, ((Number) infy.get("unmatched_quantity")).doubleValue(), 0.01);
    }

    @Test
    void taxHarvestReportShouldReturnPartialStatusWhenZerodhaTradebookCoverageIsIncomplete(@TempDir Path tempDir) throws Exception {
        Path importRoot = Files.createDirectories(tempDir.resolve("imports"));
        Files.createDirectories(tempDir.resolve("data"));
        Path csvPath = importRoot.resolve("zerodha-tradebook.csv");
        Files.writeString(csvPath, """
                Trade Date,Trading Symbol,Trade Type,Quantity,Price,Exchange,Segment,Order ID,Trade ID
                01/03/2026,INFY,BUY,5,1500,NSE,EQ,OID1,TID1
                """);

        ZerodhaTradebookService tradebookService = new ZerodhaTradebookService(
                new ZerodhaTradebookProperties(
                        importRoot.toString(),
                        tempDir.resolve("data").resolve("zerodha-tradebook.json").toString()
                ),
                stockMetadataService,
                objectMapper,
                fixedIndiaClock("2026-03-16T11:00:00+05:30")
        );
        tradebookService.importTradebook(csvPath.toString());

        CompoundToolService service = service(new ZerodhaPartialCoverageBrokerDataProvider(), fixedIndiaClock("2026-03-16T11:00:00+05:30"), tradebookService);

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);
        List<String> fullyCovered = (List<String>) result.get("brokers_fully_covered");
        List<String> partial = (List<String>) result.get("brokers_partial");
        Map<String, Object> dataSources = (Map<String, Object>) result.get("data_sources");
        Map<String, Object> zerodhaTradebook = (Map<String, Object>) dataSources.get("zerodha_tradebook");
        Map<String, Object> coveredRange = (Map<String, Object>) zerodhaTradebook.get("covered_range");

        assertEquals("PARTIAL", result.get("status"));
        assertFalse(fullyCovered.contains("zerodha"));
        assertEquals(List.of("zerodha"), partial);
        assertTrue(result.get("recommendation") != null);
        assertEquals(true, zerodhaTradebook.get("has_imports"));
        assertEquals(1, ((Number) zerodhaTradebook.get("imported_trades")).intValue());
        assertEquals("2026-03-01", coveredRange.get("from"));
        assertEquals("2026-03-01", coveredRange.get("to"));
        assertEquals(0, ((Number) zerodhaTradebook.get("unresolved_adjustments")).intValue());
    }

    @Test
    void taxHarvestReportShouldStayOkWhenOnlyIciciBrokerParticipates() throws Exception {
        CompoundToolService service = service(new IciciOnlyBrokerDataProvider(), fixedIndiaClock("2026-03-16T11:00:00+05:30"), null);

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);
        List<String> fullyCovered = (List<String>) result.get("brokers_fully_covered");
        List<String> partial = (List<String>) result.get("brokers_partial");

        assertEquals("OK", result.get("status"));
        assertEquals(List.of("icici"), fullyCovered);
        assertTrue(partial == null || partial.isEmpty());
        assertFalse(((Map<?, ?>) result.get("data_sources")).containsKey("zerodha_tradebook"));
    }

    @Test
    void taxHarvestReportShouldNotLogZerodhaCoverageWhenPortfolioIsPureIcici() throws Exception {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CompoundToolService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            CompoundToolService service = service(new IciciOnlyBrokerDataProvider(), fixedIndiaClock("2026-03-16T11:00:00+05:30"), null);

            service.taxHarvestReport();

            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertFalse(messages.stream().anyMatch(message -> message.contains("Tax harvest Zerodha coverage evaluation")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void taxHarvestReportShouldRequireTradebookWhenOnlyZerodhaBrokerParticipates(@TempDir Path tempDir) throws Exception {
        ZerodhaTradebookService tradebookService = new ZerodhaTradebookService(
                new ZerodhaTradebookProperties(
                        Files.createDirectories(tempDir.resolve("imports")).toString(),
                        tempDir.resolve("data").resolve("zerodha-tradebook.json").toString()
                ),
                stockMetadataService,
                objectMapper,
                fixedIndiaClock("2026-03-16T11:00:00+05:30")
        );
        CompoundToolService service = service(new ZerodhaOnlyBrokerDataProvider(), fixedIndiaClock("2026-03-16T11:00:00+05:30"), tradebookService);

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);
        List<String> fullyCovered = (List<String>) result.get("brokers_fully_covered");
        List<String> partial = (List<String>) result.get("brokers_partial");
        Map<String, Object> dataSources = (Map<String, Object>) result.get("data_sources");
        Map<String, Object> zerodhaTradebook = (Map<String, Object>) dataSources.get("zerodha_tradebook");

        assertEquals("NEEDS_ZERODHA_TRADEBOOK", result.get("status"));
        assertTrue(fullyCovered.isEmpty());
        assertEquals(List.of("zerodha"), partial);
        assertEquals(1, ((Number) zerodhaTradebook.get("same_day_api_trades")).intValue());
        assertEquals(false, zerodhaTradebook.get("has_imports"));
    }

    @Test
    void taxHarvestReportShouldTreatRecentSparseZerodhaImportsAsCovered(@TempDir Path tempDir) throws Exception {
        Path importRoot = Files.createDirectories(tempDir.resolve("imports"));
        Path csvPath = importRoot.resolve("zerodha-tradebook.csv");
        Files.writeString(csvPath, """
                Trade Date,Trading Symbol,Trade Type,Quantity,Price,Exchange,Segment,Order ID,Trade ID
                16/03/2026,INFY,BUY,5,1500,NSE,EQ,OID1,TID1
                16/03/2026,INFY,BUY,5,1500,NSE,EQ,OID2,TID2
                """);
        ZerodhaTradebookService tradebookService = new ZerodhaTradebookService(
                new ZerodhaTradebookProperties(
                        importRoot.toString(),
                        tempDir.resolve("data").resolve("zerodha-tradebook.json").toString()
                ),
                stockMetadataService,
                objectMapper,
                fixedIndiaClock("2026-03-18T11:00:00+05:30")
        );
        tradebookService.importTradebook(csvPath.toString());

        CompoundToolService service = service(new ZerodhaImportedRecentAccountDataProvider(), fixedIndiaClock("2026-03-18T11:00:00+05:30"), tradebookService);

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);
        List<String> fullyCovered = (List<String>) result.get("brokers_fully_covered");
        List<String> partial = (List<String>) result.get("brokers_partial");
        Map<String, Object> dataSources = (Map<String, Object>) result.get("data_sources");
        Map<String, Object> zerodhaTradebook = (Map<String, Object>) dataSources.get("zerodha_tradebook");
        List<Map<String, Object>> executedTrades = (List<Map<String, Object>>) zerodhaTradebook.get("executed_trades");

        assertEquals("OK", result.get("status"));
        assertEquals(List.of("zerodha"), fullyCovered);
        assertTrue(partial == null || partial.isEmpty());
        assertEquals(2, executedTrades.size());
        assertEquals("2026-03-16", executedTrades.getFirst().get("trade_date"));
        assertEquals("buy", executedTrades.getFirst().get("action"));
    }

    @Test
    void taxHarvestReportShouldMatchCanonicalZerodhaBuyLotsToNseHoldings() throws Exception {
        CompoundToolService service = service(new ZerodhaCanonicalTradeHoldingProvider(), fixedIndiaClock("2026-03-18T11:00:00+05:30"), null);

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);
        Map<String, Object> unrealizedPositions = (Map<String, Object>) result.get("unrealized_positions");
        List<Map<String, Object>> stcgHoldings = (List<Map<String, Object>>) unrealizedPositions.get("stcg_holdings");
        List<Map<String, Object>> estimated = (List<Map<String, Object>>) result.get("estimated_from_holdings_avg");

        assertEquals(1, stcgHoldings.size());
        assertEquals("ADANIPOWER", stcgHoldings.getFirst().get("stock"));
        assertEquals("2026-03-16", stcgHoldings.getFirst().get("buy_date"));
        assertTrue(estimated.stream().noneMatch(item -> "ADANIPOWER".equals(item.get("stock"))));
    }

    private Clock fixedIndiaClock(String isoOffsetDateTime) {
        return Clock.fixed(Instant.parse(java.time.ZonedDateTime.parse(isoOffsetDateTime).toInstant().toString()), ZoneId.of("Asia/Kolkata"));
    }

    private CompoundToolService service(
            BrokerDataProvider provider,
            Clock clock,
            ZerodhaTradebookService tradebookService
    ) {
        return CompoundToolServiceTestFactory.builder(provider, stockMetadataService, objectMapper)
                .clock(clock)
                .zerodhaTradebookService(tradebookService)
                .build();
    }

    private static final class MixedBrokerDataProvider extends BaseTaxBrokerDataProvider {
        @Override
        public List<HoldingSnapshot> getPortfolioHoldings() {
            return List.of(
                    new HoldingSnapshot("ICICIBANK", "ICICI Bank", "NSE", 5, 200, 215, 0, 0, "icici", null),
                    new HoldingSnapshot("INFY", "Infosys", "NSE", 10, 1500, 1600, 0, 0, "zerodha", null)
            );
        }

        @Override
        public List<TradeSnapshot> getTrades(LocalDate fromDate, LocalDate toDate) {
            return List.of(
                    new TradeSnapshot("ICICIBANK", "buy", 5, 200, LocalDate.of(2025, 5, 10), "icici")
            );
        }
    }

    private static final class IciciOnlyBrokerDataProvider extends BaseTaxBrokerDataProvider {
        @Override
        public List<HoldingSnapshot> getPortfolioHoldings() {
            return List.of(
                    new HoldingSnapshot("ICICIBANK", "ICICI Bank", "NSE", 5, 200, 215, 0, 0, "icici", null)
            );
        }

        @Override
        public List<TradeSnapshot> getTrades(LocalDate fromDate, LocalDate toDate) {
            return List.of(
                    new TradeSnapshot("ICICIBANK", "buy", 5, 200, LocalDate.of(2025, 5, 10), "icici")
            );
        }
    }

    private static final class ZerodhaOnlyBrokerDataProvider extends BaseTaxBrokerDataProvider {
        @Override
        public List<HoldingSnapshot> getPortfolioHoldings() {
            return List.of(
                    new HoldingSnapshot("INFY", "Infosys", "NSE", 10, 1500, 1600, 0, 0, "zerodha", null)
            );
        }

        @Override
        public List<TradeSnapshot> getTrades(LocalDate fromDate, LocalDate toDate) {
            return List.of(
                    new TradeSnapshot("INFY", "buy", 10, 1500, LocalDate.of(2026, 3, 16), "zerodha")
            );
        }
    }

    private static final class ZerodhaImportedRecentAccountDataProvider extends BaseTaxBrokerDataProvider {
        @Override
        public List<HoldingSnapshot> getPortfolioHoldings() {
            return List.of(
                    new HoldingSnapshot("INFY", "Infosys", "NSE", 10, 1500, 1600, 0, 0, "zerodha", null)
            );
        }
    }

    private static final class ZerodhaCanonicalTradeHoldingProvider extends BaseTaxBrokerDataProvider {
        @Override
        public List<HoldingSnapshot> getPortfolioHoldings() {
            return List.of(
                    new HoldingSnapshot("ADANIPOWER", "Adani Power", "NSE", 10, 150, 154.07, 0, 0, "zerodha", null)
            );
        }

        @Override
        public List<TradeSnapshot> getTrades(LocalDate fromDate, LocalDate toDate) {
            return List.of(
                    new TradeSnapshot("ADAPOW", "buy", 10, 150, LocalDate.of(2026, 3, 16), "zerodha")
            );
        }
    }

    private static final class ZerodhaPartialCoverageBrokerDataProvider extends BaseTaxBrokerDataProvider {
        @Override
        public List<HoldingSnapshot> getPortfolioHoldings() {
            return List.of(
                    new HoldingSnapshot("INFY", "Infosys", "NSE", 10, 1500, 1600, 0, 0, "zerodha", null)
            );
        }

        @Override
        public List<TradeSnapshot> getTrades(LocalDate fromDate, LocalDate toDate) {
            return List.of(
                    new TradeSnapshot("INFY", "buy", 10, 1500, LocalDate.of(2025, 5, 10), "zerodha")
            );
        }
    }

    private abstract static class BaseTaxBrokerDataProvider implements BrokerDataProvider {
        @Override
        public FundsSnapshot getFunds() {
            return new FundsSnapshot(5000, 3000, null, "composite", Map.of());
        }

        @Override
        public QuoteSnapshot getQuote(String stockCode, String exchangeCode, String productType) {
            return new QuoteSnapshot(stockCode, exchangeCode, 100, 99, 100, 101, 98, 1000, 99, 100, java.time.ZonedDateTime.now(ZoneId.of("UTC")));
        }

        @Override
        public Map<String, QuoteSnapshot> getQuotes(List<String> stockCodes, String exchangeCode, String productType) {
            return Map.of();
        }

        @Override
        public List<HistoricalCandle> getHistoricalCharts(String stockCode, String exchangeCode, String productType, String interval, LocalDate fromDate, LocalDate toDate) {
            return List.of();
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

