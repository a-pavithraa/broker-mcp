package com.broker.service;

import com.broker.model.AnalysisModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.*;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CompoundToolServiceTest {

    private ObjectMapper objectMapper;
    private StockMetadataService stockMetadataService;
    private StubBrokerDataProvider dataProvider;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        stockMetadataService = new StockMetadataService(new DefaultResourceLoader(), objectMapper, "classpath:stock-universe.csv");
        dataProvider = new StubBrokerDataProvider();
    }

    @Test
    void helperMethodNamesShouldUseJavaNativeConcurrencyTerms() {
        Set<String> compoundHelperNames = declaredMethodNames(CompoundToolService.class);
        Set<String> marketDataHelperNames = declaredMethodNames(MarketDataService.class);

        assertFalse(compoundHelperNames.contains("async"));
        assertFalse(compoundHelperNames.contains("await"));
        assertTrue(compoundHelperNames.contains("submit"));
        assertTrue(compoundHelperNames.contains("joinUnchecked"));

        assertFalse(marketDataHelperNames.contains("async"));
        assertFalse(marketDataHelperNames.contains("await"));
        assertTrue(marketDataHelperNames.contains("submit"));
        assertTrue(marketDataHelperNames.contains("joinUnchecked"));
    }

    @Test
    void portfolioSnapshot_shouldReturnComputedSummary() throws Exception {
        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.portfolioSnapshot(), Map.class);
        Map<String, Object> summary = cast(result.get("summary"));

        assertEquals(2, summary.get("holdings_count"));
        assertEquals(2200.0, number(summary.get("current_value")));
        assertEquals(200.0, number(summary.get("total_pnl")));
        assertEquals(3000.0, number(summary.get("cash_available")));
    }

    @Test
    void portfolioSnapshot_shouldSumCashAcrossBrokerFunds() throws Exception {
        dataProvider.customFunds = List.of(
                new FundsSnapshot(5_000, 3_000, "1234", "icici", Map.of()),
                new FundsSnapshot(4_000, 2_500, null, "zerodha", Map.of())
        );
        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.portfolioSnapshot(), Map.class);
        Map<String, Object> summary = cast(result.get("summary"));

        assertEquals(5500.0, number(summary.get("cash_available")));
    }

    @Test
    void portfolioSnapshot_shouldFetchHoldingsAndFundsInParallel() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        StubBrokerDataProvider parallelProvider = new StubBrokerDataProvider() {
            @Override
            public List<HoldingSnapshot> getPortfolioHoldings() {
                awaitPeer(started);
                return super.getPortfolioHoldings();
            }

            @Override
            public List<FundsSnapshot> getAllFunds() {
                awaitPeer(started);
                return super.getAllFunds();
            }
        };
        ExecutorService sharedExecutor = Executors.newFixedThreadPool(4);
        try {
            CompoundToolService service = service(parallelProvider, false, fixedIndiaClock("2026-03-16T11:00:00+05:30"), sharedExecutor);

            Map<String, Object> result = objectMapper.readValue(service.portfolioSnapshot(), Map.class);
            Map<String, Object> summary = cast(result.get("summary"));

            assertEquals(2, summary.get("holdings_count"));
        } finally {
            sharedExecutor.shutdownNow();
        }
    }

    @Test
    void portfolioSnapshot_shouldUseMergedCompositeHoldings() throws Exception {
        ConfigurableBrokerDataProvider icici = new ConfigurableBrokerDataProvider();
        icici.holdings = List.of(
                new HoldingSnapshot("INFY", "Infosys", "NSE", 10, 100, 120, 50, 2, "icici", "INE009A01021"),
                new HoldingSnapshot("TCS", "TCS", "NSE", 2, 4000, 4200, 100, 1, "icici", "INE467B01029")
        );
        icici.funds = new FundsSnapshot(5000, 3000, "1234", "icici", Map.of());

        ConfigurableBrokerDataProvider zerodha = new ConfigurableBrokerDataProvider();
        zerodha.holdings = List.of(
                new HoldingSnapshot("INFY", "Infosys", "NSE", 5, 110, 121, 25, 3, "zerodha", "INE009A01021")
        );
        zerodha.funds = new FundsSnapshot(4000, 2500, null, "zerodha", Map.of());

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CompositeBrokerGateway gateway = new CompositeBrokerGateway(icici, zerodha, executor, stockMetadataService, "zerodha", "zerodha");
            CompoundToolService service = service(gateway, false);

            Map<String, Object> result = objectMapper.readValue(service.portfolioSnapshot(), Map.class);
            Map<String, Object> summary = cast(result.get("summary"));

            assertEquals(2, summary.get("holdings_count"));
            assertEquals(10215.0, number(summary.get("current_value")), 0.01);
            assertEquals(5500.0, number(summary.get("cash_available")), 0.01);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void portfolioSnapshot_shouldPreserveCurrentTopLevelPayloadContract() throws Exception {
        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.portfolioSnapshot(), Map.class);
        Map<String, Object> marketSession = cast(result.get("market_session"));
        Map<String, Object> summary = cast(result.get("summary"));
        List<Map<String, Object>> todaysTopGainers = castList(result.get("todays_top_gainers"));
        List<Map<String, Object>> todaysTopLosers = castList(result.get("todays_top_losers"));
        List<Map<String, Object>> topHoldingsByValue = castList(result.get("top_holdings_by_value"));
        List<Map<String, Object>> biggestWinnersOverall = castList(result.get("biggest_winners_overall"));
        List<Map<String, Object>> biggestLosersOverall = castList(result.get("biggest_losers_overall"));
        List<?> dataSources = (List<?>) result.get("data_sources");

        assertTrue(marketSession != null);
        assertTrue(summary != null);
        assertTrue(summary.containsKey("holdings_count"));
        assertTrue(summary.containsKey("current_value"));
        assertTrue(summary.containsKey("total_pnl"));
        assertTrue(summary.containsKey("cash_available"));
        assertTrue(todaysTopGainers != null);
        assertTrue(todaysTopLosers != null);
        assertTrue(topHoldingsByValue != null);
        assertTrue(biggestWinnersOverall != null);
        assertTrue(biggestLosersOverall != null);
        assertTrue(dataSources != null);
        assertFalse(dataSources.isEmpty());
    }

    @Test
    void portfolioSnapshot_shouldEmitSingleSourceWarningWhenOnlyOneBrokerContributes() throws Exception {
        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.portfolioSnapshot(), Map.class);
        List<?> dataSources = (List<?>) result.get("data_sources");
        Object warning = result.get("warning");

        assertEquals(1, dataSources.size());
        assertEquals("stub", dataSources.getFirst());
        assertTrue(warning != null);
        assertTrue(String.valueOf(warning).contains("Data is from stub only"));
        assertTrue(String.valueOf(warning).contains("breeze_session_status"));
    }

    @Test
    void portfolioSnapshot_shouldNotEmitSingleSourceWarningWhenMultipleBrokersContribute() throws Exception {
        ConfigurableBrokerDataProvider icici = new ConfigurableBrokerDataProvider();
        icici.holdings = List.of(
                new HoldingSnapshot("INFY", "Infosys", "NSE", 10, 100, 120, 50, 2, "icici", "INE009A01021")
        );
        icici.funds = new FundsSnapshot(5000, 3000, "1234", "icici", Map.of());

        ConfigurableBrokerDataProvider zerodha = new ConfigurableBrokerDataProvider();
        zerodha.holdings = List.of(
                new HoldingSnapshot("TCS", "TCS", "NSE", 2, 4000, 4200, 100, 1, "zerodha", "INE467B01029")
        );
        zerodha.funds = new FundsSnapshot(4000, 2500, null, "zerodha", Map.of());

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CompositeBrokerGateway gateway = new CompositeBrokerGateway(icici, zerodha, executor, stockMetadataService, "zerodha", "zerodha");
            CompoundToolService service = service(gateway, false);

            Map<String, Object> result = objectMapper.readValue(service.portfolioSnapshot(), Map.class);
            List<?> dataSources = (List<?>) result.get("data_sources");

            assertTrue(dataSources.size() > 1);
            assertFalse(result.containsKey("warning"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void taxHarvestReport_shouldSplitLtcgAndStcg() throws Exception {
        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);
        Map<String, Object> unrealized = cast(result.get("unrealized_positions"));
        List<Map<String, Object>> ltcg = castList(unrealized.get("ltcg_holdings"));
        List<Map<String, Object>> stcg = castList(unrealized.get("stcg_holdings"));

        assertEquals(1, ltcg.size());
        assertEquals(1, stcg.size());
        assertEquals("TATAPOWER", ltcg.get(0).get("stock"));
        assertEquals("ICICIBANK", stcg.get(0).get("stock"));
    }

    @Test
    void taxHarvestReport_shouldSplitSameStockIntoLtcgAndStcgByLot() throws Exception {
        dataProvider.customHoldings = List.of(
                new HoldingSnapshot("DATPAT", "Data Patterns", "NSE", 12, 4100, 0, 0, 0, "stub", null)
        );
        dataProvider.customTrades = List.of(
                new TradeSnapshot("DATPAT", "buy", 5, 4008.50, LocalDate.now().minusMonths(50), "stub"),
                new TradeSnapshot("DATPAT", "buy", 5, 4025.00, LocalDate.now().minusMonths(50), "stub"),
                new TradeSnapshot("DATPAT", "buy", 2, 4406.00, LocalDate.now().minusMonths(2), "stub")
        );

        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);
        Map<String, Object> unrealized = cast(result.get("unrealized_positions"));
        List<Map<String, Object>> ltcg = castList(unrealized.get("ltcg_holdings"));
        List<Map<String, Object>> stcg = castList(unrealized.get("stcg_holdings"));

        double ltcgQty = ltcg.stream().mapToDouble(e -> number(e.get("quantity"))).sum();
        double stcgQty = stcg.stream().mapToDouble(e -> number(e.get("quantity"))).sum();

        assertEquals(10.0, ltcgQty, 0.01);
        assertEquals(2.0, stcgQty, 0.01);
        assertTrue(ltcg.stream().allMatch(e -> "LTCG".equals(e.get("classification"))));
        assertTrue(stcg.stream().allMatch(e -> "STCG".equals(e.get("classification"))));
    }

    @Test
    void taxHarvestReport_shouldKeepExactTwelveMonthBoundaryAsStcg() throws Exception {
        dataProvider.customHoldings = List.of(
                new HoldingSnapshot("ICICIBANK", "ICICI Bank", "NSE", 1, 100, 100, 0, 0, "stub", null)
        );
        dataProvider.customTrades = List.of(
                new TradeSnapshot("TATAPOWER", "buy", 5, 100, LocalDate.of(2025, 3, 16), "stub"),
                new TradeSnapshot("TATAPOWER", "sell", 5, 120, LocalDate.of(2026, 3, 16), "stub"),
                new TradeSnapshot("ICICIBANK", "buy", 1, 100, LocalDate.of(2025, 3, 16), "stub")
        );

        CompoundToolService service = service(dataProvider, false, fixedIndiaClock("2026-03-16T11:00:00+05:30"));

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);

        Map<String, Object> realized = cast(result.get("realized_gains_this_fy"));
        List<Map<String, Object>> sells = castList(realized.get("sells"));
        assertEquals(1, sells.size());
        assertEquals("STCG", sells.getFirst().get("classification"));

        Map<String, Object> unrealized = cast(result.get("unrealized_positions"));
        List<Map<String, Object>> ltcg = castList(unrealized.get("ltcg_holdings"));
        List<Map<String, Object>> stcg = castList(unrealized.get("stcg_holdings"));

        assertEquals(1, stcg.size());
        assertEquals("STCG", stcg.getFirst().get("classification"));
        assertTrue(ltcg.isEmpty());
    }

    @Test
    void taxHarvestReport_shouldGroupMixedBrokerTradesByCanonicalSymbol() throws Exception {
        dataProvider.customHoldings = List.of(
                new HoldingSnapshot("TATAPOWER", "Tata Power", "NSE", 7, 4100, 0, 0, 0, "composite", null)
        );
        dataProvider.customTrades = List.of(
                new TradeSnapshot("TATAPOWER", "buy", 5, 4000, LocalDate.now().minusMonths(18), "icici"),
                new TradeSnapshot("TATAPOWER", "buy", 2, 4200, LocalDate.now().minusMonths(2), "zerodha")
        );

        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);
        Map<String, Object> unrealized = cast(result.get("unrealized_positions"));
        List<Map<String, Object>> ltcg = castList(unrealized.get("ltcg_holdings"));
        List<Map<String, Object>> stcg = castList(unrealized.get("stcg_holdings"));

        assertEquals(5.0, ltcg.stream().mapToDouble(e -> number(e.get("quantity"))).sum(), 0.01);
        assertEquals(2.0, stcg.stream().mapToDouble(e -> number(e.get("quantity"))).sum(), 0.01);
        assertEquals("TATAPOWER", ltcg.getFirst().get("stock"));
        assertEquals("TATAPOWER", stcg.getFirst().get("stock"));
    }

    @Test
    void taxHarvestReport_shouldFifoMatchSellsBeforeUnrealized() throws Exception {
        dataProvider.customHoldings = List.of(
                new HoldingSnapshot("DATPAT", "Data Patterns", "NSE", 7, 4100, 0, 0, 0, "stub", null)
        );
        dataProvider.customTrades = List.of(
                new TradeSnapshot("DATPAT", "buy", 10, 4000, LocalDate.now().minusMonths(50), "stub"),
                new TradeSnapshot("DATPAT", "buy", 2, 4400, LocalDate.now().minusMonths(2), "stub"),
                new TradeSnapshot("DATPAT", "sell", 5, 4500, LocalDate.now().minusDays(10), "stub")
        );

        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);

        Map<String, Object> realized = cast(result.get("realized_gains_this_fy"));
        List<Map<String, Object>> sells = castList(realized.get("sells"));
        assertEquals(1, sells.size());
        assertEquals("LTCG", sells.getFirst().get("classification"));
        assertEquals(5.0, number(sells.getFirst().get("quantity")), 0.01);

        Map<String, Object> unrealized = cast(result.get("unrealized_positions"));
        List<Map<String, Object>> ltcg = castList(unrealized.get("ltcg_holdings"));
        List<Map<String, Object>> stcg = castList(unrealized.get("stcg_holdings"));

        double ltcgQty = ltcg.stream().mapToDouble(e -> number(e.get("quantity"))).sum();
        double stcgQty = stcg.stream().mapToDouble(e -> number(e.get("quantity"))).sum();
        assertEquals(5.0, ltcgQty, 0.01);
        assertEquals(2.0, stcgQty, 0.01);
    }

    @Test
    void taxHarvestReport_shouldIncludeIpoAllotmentUsingAvgPriceFallback() throws Exception {
        dataProvider.customHoldings = List.of(
                new HoldingSnapshot("INFY", "Infosys", "NSE", 25, 300, 0, 0, 0, "stub", null)
        );
        dataProvider.customTrades = List.of();

        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);
        Map<String, Object> unrealized = cast(result.get("unrealized_positions"));
        List<Map<String, Object>> ltcg = castList(unrealized.get("ltcg_holdings"));

        // Unmatched stocks (no trade history) are excluded from ltcg_holdings â€” buy date unknown, cannot classify
        assertEquals(0, ltcg.size());

        List<Map<String, Object>> estimated = castList(result.get("estimated_from_holdings_avg"));
        assertEquals(1, estimated.size());
        assertEquals("INFY", estimated.getFirst().get("stock"));
        assertEquals(25.0, number(estimated.getFirst().get("unmatched_quantity")), 0.01);
        assertEquals(300.0, number(estimated.getFirst().get("estimated_buy_price")), 0.01);
    }

    @Test
    void taxHarvestReport_shouldUseManualAllotmentEntriesForUnmatchedHoldings() throws Exception {
        dataProvider.customHoldings = List.of(
                new HoldingSnapshot("TATACAP", "Tata Capital", "NSE", 8, 950, 1100, 0, 0, "stub", null)
        );
        dataProvider.customTrades = List.of();

        CompoundToolService service = service(dataProvider, false, fixedIndiaClock("2026-03-20T11:00:00+05:30"));

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);
        Map<String, Object> unrealized = cast(result.get("unrealized_positions"));
        List<Map<String, Object>> stcg = castList(unrealized.get("stcg_holdings"));
        List<Map<String, Object>> estimated = castList(result.get("estimated_from_holdings_avg"));

        assertEquals(1, stcg.size());
        assertEquals("TATACAP", stcg.getFirst().get("stock"));
        assertEquals("2025-10-10", stcg.getFirst().get("buy_date"));
        assertEquals(8.0, number(stcg.getFirst().get("quantity")), 0.01);
        assertEquals(950.0, number(stcg.getFirst().get("buy_price")), 0.01);
        assertEquals(1200.0, number(stcg.getFirst().get("gain_or_loss")), 0.01);
        assertTrue(estimated.stream().noneMatch(item -> "TATACAP".equals(item.get("stock"))));
        assertFalse(result.containsKey("needs_corporate_action_review"));
    }

    @Test
    void taxHarvestReport_shouldUseSeededLgAllotmentWhenHoldingUsesExchangeSymbol() throws Exception {
        dataProvider.customHoldings = List.of(
                new HoldingSnapshot("LGEINDIA", "LG Electronics India", "NSE", 13, 1140, 1559.2, 0, 0, "stub", null)
        );
        dataProvider.customTrades = List.of();

        CompoundToolService service = service(dataProvider, false, fixedIndiaClock("2026-03-20T11:00:00+05:30"));

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);
        Map<String, Object> unrealized = cast(result.get("unrealized_positions"));
        List<Map<String, Object>> stcg = castList(unrealized.get("stcg_holdings"));
        List<Map<String, Object>> estimated = castList(result.get("estimated_from_holdings_avg"));

        assertEquals(1, stcg.size());
        assertEquals("LGEINDIA", stcg.getFirst().get("stock"));
        assertEquals("2025-10-13", stcg.getFirst().get("buy_date"));
        assertEquals(13.0, number(stcg.getFirst().get("quantity")), 0.01);
        assertEquals(1140.0, number(stcg.getFirst().get("buy_price")), 0.01);
        assertEquals(5449.6, number(stcg.getFirst().get("gain_or_loss")), 0.01);
        assertTrue(estimated.stream().noneMatch(item -> "LGEINDIA".equals(item.get("stock"))));
        assertFalse(result.containsKey("needs_corporate_action_review"));
    }

    @Test
    void taxHarvestReport_shouldFlagUnmatchedHoldingsForCorporateActionReview() throws Exception {
        dataProvider.customHoldings = List.of(
                new HoldingSnapshot("INFY", "Infosys", "NSE", 10, 500, 550, 0, 0, "stub", null)
        );
        dataProvider.customTrades = List.of(
                new TradeSnapshot("INFY", "buy", 3, 800, LocalDate.of(2024, 1, 15), "stub")
        );

        CompoundToolService service = service(dataProvider, false, fixedIndiaClock("2026-03-20T11:00:00+05:30"));

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);
        List<String> needsReview = (List<String>) result.get("needs_corporate_action_review");
        List<Map<String, Object>> reasons = castList(result.get("suspected_corporate_action_reasons"));
        List<Map<String, Object>> estimated = castList(result.get("estimated_from_holdings_avg"));

        assertEquals(List.of("INFY"), needsReview);
        assertEquals(1, reasons.size());
        assertEquals("INFY", reasons.getFirst().get("stock"));
        assertEquals(10.0, number(reasons.getFirst().get("holding_quantity")), 0.01);
        assertEquals(3.0, number(reasons.getFirst().get("matched_trade_quantity")), 0.01);
        assertEquals(7.0, number(reasons.getFirst().get("unmatched_quantity")), 0.01);
        assertTrue(String.valueOf(reasons.getFirst().get("reason")).toLowerCase().contains("review"));
        assertEquals(1, estimated.size());
        assertEquals("INFY", estimated.getFirst().get("stock"));
    }

    @Test
    void taxHarvestReport_shouldDeriveUnmatchedCostWhenPartialLotsExist() throws Exception {
        dataProvider.customHoldings = List.of(
                new HoldingSnapshot("INFY", "Infosys", "NSE", 10, 500, 0, 0, 0, "stub", null)
        );
        dataProvider.customTrades = List.of(
                new TradeSnapshot("INFY", "buy", 3, 800, LocalDate.now().minusMonths(14), "stub")
        );

        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);
        Map<String, Object> unrealized = cast(result.get("unrealized_positions"));
        List<Map<String, Object>> ltcg = castList(unrealized.get("ltcg_holdings"));

        // Only the known trade lot (3 shares at 800, LTCG) should appear in ltcg_holdings
        assertEquals(1, ltcg.size());
        Map<String, Object> tradeLot = ltcg.getFirst();
        assertEquals(3.0, number(tradeLot.get("quantity")), 0.01);
        assertEquals(800.0, number(tradeLot.get("buy_price")), 0.01);

        // The unmatched 7 shares appear in estimated_from_holdings_avg with correct derived cost
        List<Map<String, Object>> estimated = castList(result.get("estimated_from_holdings_avg"));
        assertEquals(1, estimated.size());
        assertEquals(7.0, number(estimated.getFirst().get("unmatched_quantity")), 0.01);
        double expectedUnmatchedBuyPrice = (500.0 * 10 - 800.0 * 3) / 7.0;
        assertEquals(expectedUnmatchedBuyPrice, number(estimated.getFirst().get("estimated_buy_price")), 0.01);
    }

    @Test
    void taxHarvestReport_shouldAdjustPreSplitLotsIntoCurrentQuantityAndCostBasis() throws Exception {
        dataProvider.customHoldings = List.of(
                new HoldingSnapshot("ADANIPOWER", "Adani Power", "NSE", 50, 150, 154, 0, 0, "stub", null)
        );
        dataProvider.customTrades = List.of(
                new TradeSnapshot("ADAPOW", "buy", 10, 750, LocalDate.of(2024, 6, 4), "stub")
        );

        CompoundToolService service = service(dataProvider, false, fixedIndiaClock("2026-03-20T11:00:00+05:30"));

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);
        Map<String, Object> unrealized = cast(result.get("unrealized_positions"));
        List<Map<String, Object>> ltcg = castList(unrealized.get("ltcg_holdings"));
        List<Map<String, Object>> estimated = castList(result.get("estimated_from_holdings_avg"));

        assertEquals(1, ltcg.size());
        assertEquals("ADANIPOWER", ltcg.getFirst().get("stock"));
        assertEquals(50.0, number(ltcg.getFirst().get("quantity")), 0.01);
        assertEquals(150.0, number(ltcg.getFirst().get("buy_price")), 0.01);
        assertEquals(200.0, number(ltcg.getFirst().get("gain_or_loss")), 0.01);
        assertTrue(estimated.stream().noneMatch(item -> "ADANIPOWER".equals(item.get("stock"))));
    }

    @Test
    void taxHarvestReport_shouldOnlyAdjustLotsHeldAcrossBonusRecordDate() throws Exception {
        dataProvider.customHoldings = List.of(
                new HoldingSnapshot("CDSL", "CDSL", "NSE", 7, 975.71, 1199.5, 0, 0, "stub", null)
        );
        dataProvider.customTrades = List.of(
                new TradeSnapshot("CDSL", "buy", 2, 1600, LocalDate.of(2022, 1, 14), "stub"),
                new TradeSnapshot("CDSL", "buy", 3, 1210, LocalDate.of(2025, 2, 25), "stub")
        );

        CompoundToolService service = service(dataProvider, false, fixedIndiaClock("2026-03-20T11:00:00+05:30"));

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);
        Map<String, Object> unrealized = cast(result.get("unrealized_positions"));
        List<Map<String, Object>> ltcg = castList(unrealized.get("ltcg_holdings"));
        List<Map<String, Object>> estimated = castList(result.get("estimated_from_holdings_avg"));

        assertEquals(2, ltcg.size());
        Map<String, Object> legacyLot = ltcg.stream()
                .filter(item -> "2022-01-14".equals(item.get("buy_date")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> recentLot = ltcg.stream()
                .filter(item -> "2025-02-25".equals(item.get("buy_date")))
                .findFirst()
                .orElseThrow();
        assertEquals(4.0, number(legacyLot.get("quantity")), 0.01);
        assertEquals(800.0, number(legacyLot.get("buy_price")), 0.01);
        assertEquals(1598.0, number(legacyLot.get("gain_or_loss")), 0.01);
        assertEquals(3.0, number(recentLot.get("quantity")), 0.01);
        assertEquals(1210.0, number(recentLot.get("buy_price")), 0.01);
        assertEquals(-31.5, number(recentLot.get("gain_or_loss")), 0.01);
        assertTrue(estimated.stream().noneMatch(item -> "CDSL".equals(item.get("stock"))));
    }

    @Test
    void taxHarvestReport_shouldNotMatchSellAgainstLaterBuyLot() throws Exception {
        dataProvider.customHoldings = List.of(
                new HoldingSnapshot("DATPAT", "Data Patterns", "NSE", 7, 4100, 0, 0, 0, "stub", null)
        );
        dataProvider.customTrades = List.of(
                new TradeSnapshot("DATPAT", "buy", 5, 4000, LocalDate.now().minusMonths(50), "stub"),
                new TradeSnapshot("DATPAT", "sell", 3, 4500, LocalDate.now().minusMonths(40), "stub"),
                new TradeSnapshot("DATPAT", "buy", 5, 4200, LocalDate.now().minusMonths(2), "stub")
        );

        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);
        Map<String, Object> unrealized = cast(result.get("unrealized_positions"));
        List<Map<String, Object>> ltcg = castList(unrealized.get("ltcg_holdings"));
        List<Map<String, Object>> stcg = castList(unrealized.get("stcg_holdings"));

        double ltcgQty = ltcg.stream().mapToDouble(e -> number(e.get("quantity"))).sum();
        double stcgQty = stcg.stream().mapToDouble(e -> number(e.get("quantity"))).sum();

        assertEquals(2.0, ltcgQty, 0.01);
        assertEquals(5.0, stcgQty, 0.01);
    }

    @Test
    void executeTrade_shouldStayDisabledWhenTradingFlagIsOff() throws Exception {
        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(
                service.executeTrade("ICICI Bank", "buy", 5, "market", null, true, null), Map.class);

        assertEquals("DISABLED", result.get("status"));
        assertTrue(String.valueOf(result.get("message")).contains("disabled"));
    }

    @Test
    void marketPulse_shouldCalculateSentiment() throws Exception {
        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.marketPulse(), Map.class);
        Map<String, Object> sentiment = cast(result.get("sentiment"));

        assertTrue(number(sentiment.get("pcr")) > 1.0);
        assertEquals("Bullish", sentiment.get("interpretation"));
        assertEquals(22600.0, number(sentiment.get("max_pain_strike")), 0.01);
        assertEquals(22500.0, number(sentiment.get("highest_combined_oi_strike")), 0.01);
    }

    @Test
    void marketPulse_shouldPreserveCurrentPayloadContract() throws Exception {
        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.marketPulse(), Map.class);
        Map<String, Object> marketSession = cast(result.get("market_session"));
        Map<String, Object> indices = cast(result.get("indices"));
        Map<String, Object> nifty = cast(indices.get("nifty"));
        Map<String, Object> niftyTrend = cast(result.get("nifty_trend"));
        Map<String, Object> sentiment = cast(result.get("sentiment"));

        assertTrue(result.containsKey("timestamp"));
        assertNotNull(marketSession);
        assertNotNull(indices);
        assertNotNull(niftyTrend);
        assertNotNull(sentiment);
        assertTrue(indices.containsKey("nifty"));
        assertTrue(indices.containsKey("bank_nifty"));
        assertTrue(niftyTrend.containsKey("vs_20dma"));
        assertTrue(niftyTrend.containsKey("vs_50dma"));
        assertTrue(niftyTrend.containsKey("direction_20d"));
        assertTrue(nifty.containsKey("value"));
        assertTrue(nifty.containsKey("change"));
        assertTrue(nifty.containsKey("change_pct"));
    }

    @Test
    void stockCheckup_shouldPreserveCurrentDefaultExchangePayloadContract() throws Exception {
        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.stockCheckup("TATPOW", null), Map.class);
        Map<String, Object> marketSession = cast(result.get("market_session"));
        Map<String, Object> stock = cast(result.get("stock"));
        Map<String, Object> yourPosition = cast(result.get("your_position"));
        Map<String, Object> priceLevels = cast(result.get("price_levels"));
        Map<String, Object> trend = cast(result.get("trend"));
        Map<String, Object> volume = cast(result.get("volume"));

        assertNotNull(marketSession);
        assertNotNull(stock);
        assertNotNull(yourPosition);
        assertNotNull(priceLevels);
        assertNotNull(trend);
        assertNotNull(volume);
        assertTrue(stock.containsKey("code"));
        assertTrue(stock.containsKey("name"));
        assertTrue(stock.containsKey("exchange"));
        assertTrue(priceLevels.containsKey("ltp"));
        assertTrue(priceLevels.containsKey("week_52_high"));
        assertTrue(priceLevels.containsKey("week_52_low"));
        assertTrue(trend.containsKey("exchange"));
        assertTrue(trend.containsKey("sma_50"));
        assertTrue(trend.containsKey("sma_200"));
        assertTrue(volume.containsKey("avg_volume_20d"));
        assertTrue(volume.containsKey("avg_volume_60d"));
        assertEquals("TATAPOWER", stock.get("code"));
        assertEquals("NSE", stock.get("exchange"));
    }

    @Test
    void stockCheckup_shouldExposeClosedMarketSessionOnWeekend() throws Exception {
        CompoundToolService service = service(dataProvider, false, fixedIndiaClock("2026-03-15T11:00:00+05:30"));

        Map<String, Object> result = objectMapper.readValue(service.stockCheckup("TATPOW", "NSE"), Map.class);
        Map<String, Object> marketSession = cast(result.get("market_session"));

        assertEquals(false, marketSession.get("is_open"));
        assertEquals("CLOSED_WEEKEND", marketSession.get("status"));
        assertTrue(marketSession.containsKey("last_completed_trading_day"));
        assertTrue(String.valueOf(marketSession.get("message")).contains("last completed trading session"));
    }

    @Test
    void marketPulse_shouldUsePreferredZerodhaDataFromCompositeGateway() throws Exception {
        ConfigurableBrokerDataProvider icici = new ConfigurableBrokerDataProvider();
        icici.quotes.put("NIFTY|NSE", new QuoteSnapshot("NIFTY", "NSE", 22000, 21900, 21950, 22050, 21880, 0, 0, 0, ZonedDateTime.now(ZoneOffset.UTC)));
        icici.quotes.put("CNXBAN|NSE", new QuoteSnapshot("CNXBAN", "NSE", 48000, 47800, 47900, 48100, 47750, 0, 0, 0, ZonedDateTime.now(ZoneOffset.UTC)));
        icici.histories.put("NIFTY|NSE", historySeries(21800, 5, 60));
        icici.optionChains.put("NIFTY|call", List.of(new OptionChainSnapshot(22500, 900), new OptionChainSnapshot(22600, 850)));
        icici.optionChains.put("NIFTY|put", List.of(new OptionChainSnapshot(22500, 1100), new OptionChainSnapshot(22600, 1000)));

        ConfigurableBrokerDataProvider zerodha = new ConfigurableBrokerDataProvider();
        zerodha.quotes.put("NIFTY|NSE", new QuoteSnapshot("NIFTY", "NSE", 22500, 22300, 22350, 22550, 22320, 0, 0, 0, ZonedDateTime.now(ZoneOffset.UTC)));
        zerodha.quotes.put("CNXBAN|NSE", new QuoteSnapshot("CNXBAN", "NSE", 48500, 48200, 48250, 48600, 48150, 0, 0, 0, ZonedDateTime.now(ZoneOffset.UTC)));
        zerodha.histories.put("NIFTY|NSE", historySeries(22000, 10, 60));
        zerodha.optionChains.put("NIFTY|call", List.of(new OptionChainSnapshot(22500, 1000), new OptionChainSnapshot(22600, 900)));
        zerodha.optionChains.put("NIFTY|put", List.of(new OptionChainSnapshot(22500, 1800), new OptionChainSnapshot(22600, 1600)));

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CompositeBrokerGateway gateway = new CompositeBrokerGateway(icici, zerodha, executor, stockMetadataService, "zerodha", "zerodha");
            CompoundToolService service = service(gateway, false);

            Map<String, Object> result = objectMapper.readValue(service.marketPulse(), Map.class);
            Map<String, Object> indices = cast(result.get("indices"));
            Map<String, Object> nifty = cast(indices.get("nifty"));
            Map<String, Object> bankNifty = cast(indices.get("bank_nifty"));

            assertEquals(22500.0, number(nifty.get("value")), 0.01);
            assertEquals(48500.0, number(bankNifty.get("value")), 0.01);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void portfolioHealth_shouldReturnStructuredAnalysisWhenGttLookupFails() throws Exception {
        dataProvider.failGttOrders = true;
        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.portfolioHealth(), Map.class);
        Map<String, Object> facts = cast(result.get("facts"));
        Map<String, Object> heuristics = cast(result.get("heuristics"));
        Map<String, Object> portfolioSummary = cast(facts.get("portfolio_summary"));
        Map<String, Object> marketContext = cast(facts.get("market_context"));
        List<Map<String, Object>> riskFlags = castList(heuristics.get("flags"));
        List<Map<String, Object>> importantSectorGaps = castList(heuristics.get("important_sector_gaps"));
        List<String> scopeLimitations = (List<String>) result.get("scope_limitations");

        assertEquals(2, portfolioSummary.get("total_stocks"));
        assertTrue(marketContext.containsKey("nifty_trailing_1yr_return_pct"));
        assertFalse(marketContext.containsKey("outperformance_pct"));
        assertTrue(portfolioSummary.containsKey("total_return_since_purchase_pct"));
        assertFalse(portfolioSummary.containsKey("overall_return_pct"));
        assertTrue(scopeLimitations.stream()
                .anyMatch(item -> item.contains("not directly comparable")));
        assertTrue(scopeLimitations.stream()
                .anyMatch(item -> item.contains("Protective GTT status could not be evaluated")));
        assertTrue(importantSectorGaps.stream().anyMatch(item -> "Information Technology".equals(item.get("sector"))));
        assertTrue(importantSectorGaps.stream().anyMatch(item -> "Healthcare".equals(item.get("sector"))));
        assertTrue(importantSectorGaps.stream().anyMatch(item -> "Fast Moving Consumer Goods".equals(item.get("sector"))));
        assertFalse(riskFlags.stream().anyMatch(flag -> "no_debt_allocation".equals(flag.get("name"))));
        assertFalse(riskFlags.stream().anyMatch(flag -> "no_protective_gtt".equals(flag.get("name"))));
    }

    @Test
    void portfolioHealth_shouldPreserveCurrentPayloadContract() throws Exception {
        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.portfolioHealth(), Map.class);
        Map<String, Object> facts = cast(result.get("facts"));
        Map<String, Object> heuristics = cast(result.get("heuristics"));

        assertTrue(result.containsKey("facts"));
        assertTrue(result.containsKey("heuristics"));
        assertTrue(result.containsKey("scope_limitations"));
        assertTrue(result.containsKey("data_coverage"));
        assertTrue(facts.containsKey("market_session"));
        assertTrue(facts.containsKey("portfolio_summary"));
        assertTrue(facts.containsKey("concentration"));
        assertTrue(facts.containsKey("group_exposure"));
        assertTrue(facts.containsKey("sector_allocation"));
        assertTrue(facts.containsKey("market_context"));
        assertTrue(heuristics.containsKey("heuristic_score"));
        assertTrue(heuristics.containsKey("score_methodology"));
        assertTrue(heuristics.containsKey("flags"));
        assertTrue(heuristics.containsKey("positions_to_review"));
        assertTrue(heuristics.containsKey("important_sector_gaps"));
    }

    @Test
    void portfolioHealth_shouldUseValueWeightedLossesAndReportCoverage() throws Exception {
        dataProvider.customHoldings = List.of(
                new HoldingSnapshot("TATAPOWER", "Tata Power", "NSE", 20, 100, 50, 0, -5, "stub", null),
                new HoldingSnapshot("UNKNOWN1", "Unknown One", "NSE", 1, 100, 110, 0, 1, "stub", null)
        );
        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.portfolioHealth(), Map.class);
        Map<String, Object> heuristics = cast(result.get("heuristics"));
        Map<String, Object> dataCoverage = cast(result.get("data_coverage"));
        List<Map<String, Object>> riskFlags = castList(heuristics.get("flags"));
        List<Map<String, Object>> positionsToReview = castList(heuristics.get("positions_to_review"));

        Map<String, Object> lossValueFlag = riskFlags.stream()
                .filter(flag -> "loss_value_over_50pct".equals(flag.get("name")))
                .findFirst()
                .orElseThrow();
        assertEquals(true, lossValueFlag.get("triggered"));
        assertEquals(90.09, number(cast(lossValueFlag.get("observed")).get("value_pct")), 0.01);
        assertTrue(String.valueOf(lossValueFlag.get("detail")).contains("above the 50.0% review threshold"));

        assertEquals(90.09, number(dataCoverage.get("pct_portfolio_value_with_sector")), 0.01);
        List<String> unresolvedStocks = (List<String>) dataCoverage.get("unresolved_stocks");
        assertTrue(unresolvedStocks.contains("UNKNOWN1"));

        Map<String, Object> tataReview = positionsToReview.stream()
                .filter(item -> "TATAPOWER".equals(item.get("code")))
                .findFirst()
                .orElseThrow();
        assertEquals(true, tataReview.get("counts_in_score"));
    }

    @Test
    void portfolioHealth_shouldExposeClosedMarketContextOnSunday() throws Exception {
        CompoundToolService service = service(dataProvider, false, fixedIndiaClock("2026-03-15T11:00:00+05:30"));

        Map<String, Object> result = objectMapper.readValue(service.portfolioHealth(), Map.class);
        Map<String, Object> facts = cast(result.get("facts"));
        Map<String, Object> marketSession = cast(facts.get("market_session"));
        List<String> scopeLimitations = (List<String>) result.get("scope_limitations");

        assertEquals(false, marketSession.get("is_open"));
        assertEquals("CLOSED_WEEKEND", marketSession.get("status"));
        assertEquals("2026-03-13", marketSession.get("last_completed_trading_day"));
        assertTrue(String.valueOf(marketSession.get("message")).contains("last completed trading session"));
        assertTrue(scopeLimitations.stream().anyMatch(item -> item.contains("Market is currently closed")));
    }

    @Test
    void portfolioHealth_shouldTriggerGroupFlagAtTwentyFivePercent() throws Exception {
        dataProvider.customHoldings = List.of(
                new HoldingSnapshot("TATAPOWER", "Tata Power", "NSE", 10, 100, 120, 0, 0, "stub", null),
                new HoldingSnapshot("TCS", "TCS", "NSE", 2, 4000, 4000, 0, 0, "stub", null),
                new HoldingSnapshot("UNKNOWN1", "Unknown One", "NSE", 20, 100, 100, 0, 0, "stub", null)
        );
        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.portfolioHealth(), Map.class);
        Map<String, Object> heuristics = cast(result.get("heuristics"));
        List<Map<String, Object>> riskFlags = castList(heuristics.get("flags"));

        Map<String, Object> groupFlag = riskFlags.stream()
                .filter(flag -> "single_group_over_25pct".equals(flag.get("name")))
                .findFirst()
                .orElseThrow();

        assertEquals(true, groupFlag.get("triggered"));
        assertEquals("Tata Group", cast(groupFlag.get("observed")).get("group"));
        assertEquals(82.14, number(cast(groupFlag.get("observed")).get("value_pct")), 0.01);
    }

    @Test
    void portfolioHealth_shouldReportConcentrationMetrics() throws Exception {
        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.portfolioHealth(), Map.class);
        Map<String, Object> facts = cast(result.get("facts"));
        Map<String, Object> concentration = cast(facts.get("concentration"));

        assertEquals(100.0, number(concentration.get("top_5_weight_pct")), 0.01);
        assertEquals(0.5041, number(concentration.get("hhi_index")), 0.0001);
        assertEquals(0.5, number(concentration.get("equal_weight_reference_hhi")), 0.0001);
        assertEquals(1.01, number(concentration.get("concentration_ratio_to_equal_weight")), 0.01);
        assertEquals(1.98, number(concentration.get("effective_stock_count")), 0.01);
    }

    @Test
    void executeTrade_shouldRejectConfirmedOrderWhenFundsAreInsufficient() throws Exception {
        CompoundToolService service = service(dataProvider, true, fixedIndiaClock("2026-03-16T11:00:00+05:30"));

        Map<String, Object> result = objectMapper.readValue(
                service.executeTrade("ICICI Bank", "buy", 20, "market", null, true, null), Map.class);

        assertEquals("REJECTED", result.get("status"));
        assertTrue(String.valueOf(result.get("message")).contains("Insufficient available funds"));
        assertEquals(0, dataProvider.placeOrderCalls);
        Map<String, Object> order = cast(result.get("order"));
        Map<String, Object> validation = cast(order.get("validation"));
        assertEquals(false, validation.get("ready_to_place"));
    }

    @Test
    void orderPreview_shouldWarnAndDisablePlacementWhenMarketIsClosed() throws Exception {
        CompoundToolService service = service(dataProvider, true, fixedIndiaClock("2026-03-15T11:00:00+05:30"));

        Map<String, Object> result = objectMapper.readValue(
                service.orderPreview("ICICI Bank", "buy", 5, "market", null, null), Map.class);

        Map<String, Object> marketSession = cast(result.get("market_session"));
        Map<String, Object> validation = cast(result.get("validation"));
        List<String> messages = (List<String>) validation.get("messages");

        assertEquals(false, marketSession.get("is_open"));
        assertEquals(false, validation.get("market_open"));
        assertEquals(false, validation.get("ready_to_place"));
        assertTrue(messages.stream().anyMatch(item -> item.contains("Market is currently closed")));
    }

    @Test
    void orderPreview_shouldPreserveCurrentPayloadContract() throws Exception {
        CompoundToolService service = service(dataProvider, true, fixedIndiaClock("2026-03-16T11:00:00+05:30"));

        Map<String, Object> result = objectMapper.readValue(
                service.orderPreview("ICICI Bank", "sell", 2, "market", null, null), Map.class);
        Map<String, Object> marketSession = cast(result.get("market_session"));
        Map<String, Object> orderSummary = cast(result.get("order_summary"));
        Map<String, Object> charges = cast(result.get("charges"));
        Map<String, Object> positionImpact = cast(result.get("position_impact"));
        Map<String, Object> validation = cast(result.get("validation"));
        List<String> messages = (List<String>) validation.get("messages");

        assertNotNull(marketSession);
        assertNotNull(orderSummary);
        assertTrue(orderSummary.containsKey("stock"));
        assertTrue(orderSummary.containsKey("action"));
        assertTrue(orderSummary.containsKey("quantity"));
        assertTrue(orderSummary.containsKey("price"));
        assertTrue(orderSummary.containsKey("gross_value"));
        assertNotNull(charges);
        assertTrue(charges.containsKey("total_charges"));
        assertTrue(((Number) result.get("net_amount")).doubleValue() > 0);
        assertNotNull(positionImpact);
        assertTrue(positionImpact.containsKey("avg_buy_price"));
        assertTrue(positionImpact.containsKey("realized_pnl"));
        assertTrue(positionImpact.containsKey("loss_type"));
        assertTrue(validation.containsKey("sufficient_quantity"));
        assertTrue(validation.containsKey("sufficient_funds"));
        assertTrue(validation.containsKey("market_open"));
        assertTrue(validation.containsKey("ready_to_place"));
        assertTrue(validation.containsKey("messages"));
        assertEquals(true, validation.get("market_open"));
        assertEquals(true, validation.get("ready_to_place"));
        assertFalse(messages.isEmpty());
    }

    @Test
    void orderPreview_shouldBuildCanonicalBrokerAgnosticBody() throws Exception {
        CompoundToolService service = service(dataProvider, true, fixedIndiaClock("2026-03-16T11:00:00+05:30"));

        service.orderPreview("ICICI Bank", "buy", 5, "market", null, null);

        assertEquals("ICICIBANK", dataProvider.lastPreviewBody.get("symbol"));
        assertEquals("NSE", dataProvider.lastPreviewBody.get("exchange"));
        assertEquals("cash", dataProvider.lastPreviewBody.get("product"));
        assertFalse(dataProvider.lastPreviewBody.containsKey("stock_code"));
        assertFalse(dataProvider.lastPreviewBody.containsKey("exchange_code"));
    }

    @Test
    void executeTrade_shouldSendCanonicalExecutionBody() throws Exception {
        CompoundToolService service = service(dataProvider, true, fixedIndiaClock("2026-03-16T11:00:00+05:30"));

        Map<String, Object> result = objectMapper.readValue(
                service.executeTrade("ICICI Bank", "buy", 5, "market", null, true, null), Map.class);

        assertEquals(1, dataProvider.placeOrderCalls);
        assertEquals("ICICIBANK", dataProvider.lastOrderBody.get("symbol"));
        assertEquals("NSE", dataProvider.lastOrderBody.get("exchange"));
        assertFalse(dataProvider.lastOrderBody.containsKey("stock_code"));
        assertEquals("ICICIBANK", result.get("stock"));
    }

    @Test
    void executeTrade_shouldPreserveCurrentConfirmedSuccessPayloadContract() throws Exception {
        CompoundToolService service = service(dataProvider, true, fixedIndiaClock("2026-03-16T11:00:00+05:30"));

        Map<String, Object> result = objectMapper.readValue(
                service.executeTrade("ICICI Bank", "buy", 5, "market", null, true, null), Map.class);
        Map<String, Object> marketSession = cast(result.get("market_session"));
        Map<String, Object> brokerResponse = cast(result.get("broker_response"));

        assertEquals("PLACED", result.get("status"));
        assertNotNull(result.get("order_id"));
        assertFalse(String.valueOf(result.get("order_id")).isBlank());
        assertEquals("ICICIBANK", result.get("stock"));
        assertEquals("buy", result.get("action"));
        assertEquals(5, result.get("quantity"));
        assertTrue(((Number) result.get("execution_price")).doubleValue() > 0);
        assertNotNull(result.get("message"));
        assertNotNull(marketSession);
        assertNotNull(brokerResponse);
        assertTrue(brokerResponse.containsKey("message"));
    }

    @Test
    void setStopLosses_shouldReportPartialWhenAnyGttPlacementFails() throws Exception {
        dataProvider.gttFailures.add("ICICIBANK");
        CompoundToolService service = service(dataProvider, true);

        Map<String, Object> result = objectMapper.readValue(
                service.setStopLosses(List.of("TATPOW", "ICIBAN"), 10, true, null), Map.class);

        assertEquals("PARTIAL", result.get("status"));
        List<Map<String, Object>> placedOrders = castList(result.get("placed_orders"));
        List<Map<String, Object>> failedOrders = castList(result.get("failed_orders"));
        assertEquals(1, placedOrders.size());
        assertEquals("TATAPOWER", placedOrders.getFirst().get("stock"));
        assertEquals(1, failedOrders.size());
        assertEquals("ICICIBANK", failedOrders.getFirst().get("stock"));
        assertEquals("ICICIBANK", dataProvider.lastGttBody.get("symbol"));
        assertFalse(dataProvider.lastGttBody.containsKey("stock_code"));
    }

    @Test
    void setStopLosses_shouldPreserveCurrentPayloadContract() throws Exception {
        CompoundToolService service = service(dataProvider, true);

        Map<String, Object> result = objectMapper.readValue(
                service.setStopLosses(List.of("ICICIBANK"), 10, false, null), Map.class);

        Map<String, Object> marketSession = cast(result.get("market_session"));
        List<Map<String, Object>> stopLosses = castList(result.get("stop_losses"));
        List<Map<String, Object>> placedOrders = castList(result.get("placed_orders"));
        List<Map<String, Object>> failedOrders = castList(result.get("failed_orders"));
        List<?> skippedHasGtt = (List<?>) result.get("skipped_has_gtt");
        List<?> skippedEtfMf = (List<?>) result.get("skipped_etf_mf");
        List<?> skippedNoQuote = (List<?>) result.get("skipped_no_quote");

        assertNotNull(marketSession);
        assertTrue(result.containsKey("status"));
        assertEquals("PREVIEW", result.get("status"));
        assertTrue(result.containsKey("stop_losses"));
        assertTrue(result.containsKey("placed_orders"));
        assertTrue(result.containsKey("failed_orders"));
        assertTrue(result.containsKey("skipped_has_gtt"));
        assertTrue(result.containsKey("skipped_etf_mf"));
        assertTrue(result.containsKey("skipped_no_quote"));
        assertTrue(result.containsKey("total_orders"));
        assertEquals(1, stopLosses.size());
        Map<String, Object> firstStopLoss = stopLosses.getFirst();
        assertTrue(firstStopLoss.containsKey("stock"));
        assertTrue(firstStopLoss.containsKey("qty"));
        assertTrue(firstStopLoss.containsKey("current_price"));
        assertTrue(firstStopLoss.containsKey("trigger_price"));
        assertTrue(firstStopLoss.containsKey("potential_loss_if_hit"));
        assertTrue(placedOrders.isEmpty());
        assertTrue(failedOrders.isEmpty());
        assertTrue(skippedHasGtt.isEmpty());
        assertTrue(skippedEtfMf.isEmpty());
        assertTrue(skippedNoQuote.isEmpty());
        assertEquals(1.0, number(result.get("total_orders")), 0.01);
    }

    @Test
    void executeTrade_shouldRejectWhenMarketIsClosed() throws Exception {
        CompoundToolService service = service(dataProvider, true, fixedIndiaClock("2026-03-15T11:00:00+05:30"));

        Map<String, Object> result = objectMapper.readValue(
                service.executeTrade("ICICI Bank", "buy", 5, "market", null, true, null), Map.class);

        assertEquals("REJECTED", result.get("status"));
        assertTrue(String.valueOf(result.get("message")).contains("Market is currently closed"));
        assertEquals(0, dataProvider.placeOrderCalls);
        Map<String, Object> marketSession = cast(result.get("market_session"));
        assertEquals("CLOSED_WEEKEND", marketSession.get("status"));
    }

    @Test
    void taxHarvestReport_shouldCapPotentialTaxSavedByAvailableTaxableGains() throws Exception {
        dataProvider.customHoldings = List.of(
                new HoldingSnapshot("TATAPOWER", "Tata Power", "NSE", 10, 100, 50, 0, 0, "stub", null)
        );
        dataProvider.customTrades = List.of(
                new TradeSnapshot("TATAPOWER", "buy", 10, 100, LocalDate.now().minusMonths(3), "stub")
        );
        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);
        Map<String, Object> potentialTaxSaved = cast(result.get("potential_tax_saved"));

        assertEquals(0.0, number(potentialTaxSaved.get("conservative_estimate")), 0.01);
        assertEquals(0.0, number(potentialTaxSaved.get("maximum_estimate")), 0.01);
        assertEquals(0.0, number(potentialTaxSaved.get("taxable_stcg_available")), 0.01);
    }

    @Test
    void taxHarvestReport_shouldExposeUiFriendlyHarvestCandidateFields() throws Exception {
        dataProvider.customHoldings = List.of(
                new HoldingSnapshot("TATAPOWER", "Tata Power", "NSE", 10, 100, 70, 0, 0, "icici", null)
        );
        dataProvider.customTrades = List.of(
                new TradeSnapshot("TATAPOWER", "buy", 10, 100, LocalDate.now().minusMonths(15), "icici")
        );
        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);
        List<Map<String, Object>> harvestCandidates = castList(result.get("harvest_candidates"));

        assertEquals(1, harvestCandidates.size());
        Map<String, Object> candidate = harvestCandidates.getFirst();
        assertEquals("TATAPOWER", candidate.get("stock"));
        assertEquals("LTCG", candidate.get("classification"));
        assertEquals("LTCG", candidate.get("holding_period"));
        assertEquals(10.0, number(candidate.get("quantity")), 0.01);
        assertEquals(100.0, number(candidate.get("buy_price")), 0.01);
        assertEquals(70.0, number(candidate.get("current_price")), 0.01);
        assertEquals(300.0, number(candidate.get("loss_amount")), 0.01);
        assertEquals("icici", candidate.get("broker"));
        assertTrue(candidate.containsKey("holding_months"));
        assertTrue(candidate.containsKey("tax_impact"));
    }

    @Test
    void taxHarvestReport_shouldPreserveCurrentTopLevelPayloadContract() throws Exception {
        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.taxHarvestReport(), Map.class);

        assertTrue(result.containsKey("status"));
        assertTrue(result.containsKey("brokers_fully_covered"));
        assertTrue(result.containsKey("financial_year"));
        assertTrue(result.containsKey("data_window"));
        assertTrue(result.containsKey("realized_gains_this_fy"));
        assertTrue(result.containsKey("unrealized_positions"));
        assertTrue(result.containsKey("combined_estimate"));
        assertTrue(result.containsKey("harvest_candidates"));
        assertTrue(result.containsKey("total_harvestable_loss"));
        assertTrue(result.containsKey("potential_tax_saved"));
        assertTrue(result.containsKey("estimated_from_holdings_avg"));
        assertTrue(result.containsKey("data_sources"));
        assertTrue(result.get("status") != null);
        assertTrue(result.get("brokers_fully_covered") != null);
        assertTrue(result.get("data_sources") != null);
    }

    @Test
    void stockCheckup_shouldUseExchangeSpecificPriceLevelsWhenBothIsRequested() throws Exception {
        CompoundToolService service = service(dataProvider, false);

        Map<String, Object> result = objectMapper.readValue(service.stockCheckup("TATPOW", "BOTH"), Map.class);
        Map<String, Object> stock = cast(result.get("stock"));
        Map<String, Object> nseLevels = cast(result.get("price_levels_nse"));
        Map<String, Object> bseLevels = cast(result.get("price_levels_bse"));
        Map<String, Object> trend = cast(result.get("trend"));

        assertEquals("TATAPOWER", stock.get("code"));
        assertNotEquals(number(nseLevels.get("week_52_high")), number(bseLevels.get("week_52_high")), 0.01);
        assertEquals("NSE", trend.get("exchange"));
    }

    @Test
    void stockCheckup_shouldFindHoldingWhenProviderReturnsCanonicalCode() throws Exception {
        ConfigurableBrokerDataProvider provider = new ConfigurableBrokerDataProvider();
        provider.holdings = List.of(
                new HoldingSnapshot("ICIBAN", "ICICI Bank", "NSE", 5, 200, 205, 0, 1, "stub", null)
        );
        provider.quotes.put("ICICIBANK|NSE", new QuoteSnapshot("ICICIBANK", "NSE", 205, 200, 201, 206, 199, 1000, 204, 205, ZonedDateTime.now(ZoneOffset.UTC)));
        provider.histories.put("ICICIBANK|NSE", historySeries(180, 0.1, 260));

        CompoundToolService service = service(provider, false);

        Map<String, Object> result = objectMapper.readValue(service.stockCheckup("ICICIBANK", "NSE"), Map.class);
        Map<String, Object> yourPosition = cast(result.get("your_position"));

        assertEquals(5.0, number(yourPosition.get("quantity")), 0.01);
        assertEquals(200.0, number(yourPosition.get("avg_price")), 0.01);
    }

    @Test
    void stockCheckup_shouldFindHoldingWhenHoldingUsesIsinJoinKey() throws Exception {
        ConfigurableBrokerDataProvider provider = new ConfigurableBrokerDataProvider();
        provider.holdings = List.of(
                new HoldingSnapshot("ADAPOW", "Adani Power", "NSE", 7, 510, 525, 0, 1, "stub", "INE814H01029")
        );
        provider.quotes.put("ADANIPOWER|NSE", new QuoteSnapshot("ADANIPOWER", "NSE", 525, 520, 521, 526, 519, 1000, 524, 525, ZonedDateTime.now(ZoneOffset.UTC)));
        provider.histories.put("ADANIPOWER|NSE", historySeries(450, 0.3, 260));

        CompoundToolService service = service(provider, false);

        Map<String, Object> result = objectMapper.readValue(service.stockCheckup("Adani Power", "NSE"), Map.class);
        Map<String, Object> yourPosition = cast(result.get("your_position"));

        assertEquals(7.0, number(yourPosition.get("quantity")), 0.01);
        assertEquals(510.0, number(yourPosition.get("avg_price")), 0.01);
    }

    @Test
    void setStopLosses_shouldNormalizeCanonicalHoldingAndExistingGttSymbols() throws Exception {
        ConfigurableBrokerDataProvider provider = new ConfigurableBrokerDataProvider();
        provider.holdings = List.of(
                new HoldingSnapshot("ICIBAN", "ICICI Bank", "NSE", 5, 200, 205, 0, 1, "stub", null)
        );
        provider.gttOrders = List.of(
                new GttOrderSnapshot("GTT-1", "ICICIBANK", 5, "stub")
        );
        provider.quotes.put("ICICIBANK|NSE", new QuoteSnapshot("ICICIBANK", "NSE", 205, 200, 201, 206, 199, 1000, 204, 205, ZonedDateTime.now(ZoneOffset.UTC)));
        provider.placeGttOrderResponse = JsonMapper.builder().build().valueToTree(Map.of("Success", Map.of("order_id", "GTT-NEW")));

        CompoundToolService service = service(provider, true);

        Map<String, Object> result = objectMapper.readValue(service.setStopLosses(List.of("ICICIBANK"), 10, true, "NSE"), Map.class);
        List<Map<String, Object>> stopLosses = castList(result.get("stop_losses"));
        List<Map<String, Object>> placedOrders = castList(result.get("placed_orders"));
        List<String> skippedHasGtt = (List<String>) result.get("skipped_has_gtt");

        assertEquals(List.of("ICICIBANK"), skippedHasGtt);
        assertTrue(stopLosses.isEmpty());
        assertTrue(placedOrders.isEmpty());
    }

    @Test
    void shutdownAnalysisExecutor_shouldOnlyCloseOwnedPool() {
        ExecutorService sharedExecutor = Executors.newSingleThreadExecutor();
        try {
            CompoundToolService sharedService = service(dataProvider, false, fixedIndiaClock("2026-03-16T11:00:00+05:30"), sharedExecutor);

            sharedService.shutdownAnalysisExecutor();

            assertFalse(sharedExecutor.isShutdown());
        } finally {
            sharedExecutor.shutdownNow();
        }

        CompoundToolService ownedService = service(dataProvider, false, fixedIndiaClock("2026-03-16T11:00:00+05:30"), null);

        ownedService.shutdownAnalysisExecutor();

        assertTrue(ownedService.isAnalysisExecutorShutdown());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private double number(Object value) {
        return ((Number) value).doubleValue();
    }

    private CompoundToolService service(BrokerDataProvider provider, boolean tradingEnabled) {
        return CompoundToolServiceTestFactory.builder(provider, stockMetadataService, objectMapper)
                .tradingEnabled(tradingEnabled)
                .build();
    }

    private CompoundToolService service(BrokerDataProvider provider, boolean tradingEnabled, Clock clock) {
        return CompoundToolServiceTestFactory.builder(provider, stockMetadataService, objectMapper)
                .tradingEnabled(tradingEnabled)
                .clock(clock)
                .build();
    }

    private CompoundToolService service(
            BrokerDataProvider provider,
            boolean tradingEnabled,
            Clock clock,
            ExecutorService analysisExecutor
    ) {
        return CompoundToolServiceTestFactory.builder(provider, stockMetadataService, objectMapper)
                .tradingEnabled(tradingEnabled)
                .clock(clock)
                .analysisExecutor(analysisExecutor)
                .build();
    }

    private Clock fixedIndiaClock(String isoOffsetDateTime) {
        return Clock.fixed(Instant.parse(ZonedDateTime.parse(isoOffsetDateTime).toInstant().toString()), ZoneId.of("Asia/Kolkata"));
    }

    private Set<String> declaredMethodNames(Class<?> type) {
        Set<String> names = new HashSet<>();
        for (Method method : type.getDeclaredMethods()) {
            names.add(method.getName());
        }
        return names;
    }

    private List<HistoricalCandle> historySeries(double start, double dailyStep, int days) {
        return java.util.stream.IntStream.range(0, days)
                .mapToObj(index -> {
                    double close = start + (index * dailyStep);
                    return new HistoricalCandle(
                            ZonedDateTime.now(ZoneOffset.UTC).minusDays(days - index),
                            close - 1,
                            close + 1,
                            close - 2,
                            close,
                            10_000 + index,
                            0
                    );
                })
                .toList();
    }

    private void awaitPeer(CountDownLatch started) {
        started.countDown();
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static class StubBrokerDataProvider implements BrokerDataProvider {

        private boolean failGttOrders;
        private List<HoldingSnapshot> customHoldings;
        private List<FundsSnapshot> customFunds;
        private List<TradeSnapshot> customTrades;
        private final Set<String> gttFailures = new HashSet<>();
        private int placeOrderCalls;
        private Map<String, String> lastPreviewBody;
        private Map<String, String> lastOrderBody;
        private Map<String, String> lastGttBody;

        @Override
        public List<HoldingSnapshot> getPortfolioHoldings() {
            if (customHoldings != null) return customHoldings;
            return List.of(
                    new HoldingSnapshot("TATAPOWER", "Tata Power", "NSE", 10, 100, 120, 0, 1.69, "stub", null),
                    new HoldingSnapshot("ICICIBANK", "ICICI Bank", "NSE", 5, 200, 200, 0, 2.56, "stub", null)
            );
        }

        @Override
        public FundsSnapshot getFunds() {
            if (customFunds != null && !customFunds.isEmpty()) {
                return customFunds.getFirst();
            }
            return new FundsSnapshot(5000, 3000, "1234", "stub", Map.of());
        }

        @Override
        public List<FundsSnapshot> getAllFunds() {
            if (customFunds != null) {
                return customFunds;
            }
            return BrokerDataProvider.super.getAllFunds();
        }

        @Override
        public QuoteSnapshot getQuote(String stockCode, String exchangeCode, String productType) {
            return switch (stockCode) {
                case "TATAPOWER" -> new QuoteSnapshot(stockCode, exchangeCode, 120, 118, 119, 121, 117, 10_000, 119, 120, now());
                case "ICICIBANK" -> new QuoteSnapshot(stockCode, exchangeCode, 200, 195, 198, 202, 194, 11_000, 199, 200, now());
                case "NIFTY" -> new QuoteSnapshot(stockCode, exchangeCode, 22500, 22300, 22350, 22550, 22320, 0, 0, 0, now());
                case "CNXBAN" -> new QuoteSnapshot(stockCode, exchangeCode, 48500, 48200, 48250, 48600, 48150, 0, 0, 0, now());
                default -> new QuoteSnapshot(stockCode, exchangeCode, 100, 98, 99, 101, 97, 1_000, 99, 100, now());
            };
        }

        @Override
        public Map<String, QuoteSnapshot> getQuotes(List<String> stockCodes, String exchangeCode, String productType) {
            Map<String, QuoteSnapshot> result = new java.util.LinkedHashMap<>();
            for (String code : stockCodes) {
                result.put(code, getQuote(code, exchangeCode, productType));
            }
            return result;
        }

        @Override
        public List<HistoricalCandle> getHistoricalCharts(
                String stockCode,
                String exchangeCode,
                String productType,
                String interval,
                LocalDate fromDate,
                LocalDate toDate
        ) {
            int days = 260;
            if ("NIFTY".equals(stockCode)) {
                return series(22000, 10, days);
            }
            if ("TATAPOWER".equals(stockCode)) {
                if ("BSE".equals(exchangeCode)) {
                    return series(150, 0.3, days);
                }
                return series(90, 0.2, days);
            }
            if ("ICICIBANK".equals(stockCode)) {
                return series(180, 0.1, days);
            }
            return series(100, 0.1, days);
        }

        @Override
        public List<TradeSnapshot> getTrades(LocalDate fromDate, LocalDate toDate) {
            if (customTrades != null) return customTrades;
            return List.of(
                    new TradeSnapshot("TATAPOWER", "buy", 10, 100, LocalDate.now().minusMonths(14), "stub"),
                    new TradeSnapshot("ICICIBANK", "buy", 5, 200, LocalDate.now().minusMonths(3), "stub")
            );
        }

        @Override
        public List<GttOrderSnapshot> getGttOrders() {
            if (failGttOrders) {
                throw new RuntimeException("Exchange-code should be 'nfo'.");
            }
            return List.of();
        }

        @Override
        public List<OptionChainSnapshot> getOptionChain(String stockCode, String expiryDate, String right) {
            if ("call".equals(right)) {
                return List.of(new OptionChainSnapshot(22500, 1000), new OptionChainSnapshot(22600, 900));
            }
            return List.of(new OptionChainSnapshot(22500, 1800), new OptionChainSnapshot(22600, 1600));
        }

        @Override
        public tools.jackson.databind.JsonNode previewOrder(Map<String, String> body) {
            lastPreviewBody = body;
            return objectNode(Map.of(
                    "Success", Map.of(
                            "brokerage", 15,
                            "exchange_turnover_charges", 2,
                            "stamp_duty", 1,
                            "stt", 4,
                            "sebi_charges", 1,
                            "gst", 3,
                            "total_other_charges", 11,
                            "total_brokerage", 15
                    )
            ));
        }

        @Override
        public tools.jackson.databind.JsonNode placeOrder(Map<String, String> body) {
            placeOrderCalls++;
            lastOrderBody = body;
            return objectNode(Map.of("Success", Map.of("order_id", "ORD-1", "message", "Placed")));
        }

        @Override
        public tools.jackson.databind.JsonNode getOrderDetail(String exchangeCode, String orderId) {
            return objectNode(Map.of("Success", Map.of("message", "Placed")));
        }

        @Override
        public tools.jackson.databind.JsonNode placeGttOrder(Map<String, String> body) {
            lastGttBody = body;
            if (gttFailures.contains(body.get("symbol"))) {
                return objectNode(Map.of("Error", "Rejected by broker"));
            }
            return objectNode(Map.of("Success", Map.of("order_id", "GTT-1")));
        }

        private List<HistoricalCandle> series(double start, double dailyStep, int days) {
            return java.util.stream.IntStream.range(0, days)
                    .mapToObj(index -> {
                        double close = start + (index * dailyStep);
                        return new HistoricalCandle(
                                ZonedDateTime.now(ZoneOffset.UTC).minusDays(days - index),
                                close - 1,
                                close + 1,
                                close - 2,
                                close,
                                10_000 + index,
                                0
                        );
                    })
                    .toList();
        }

        private ZonedDateTime now() {
            return ZonedDateTime.now(ZoneOffset.UTC);
        }

        private tools.jackson.databind.JsonNode objectNode(Map<String, Object> payload) {
            return JsonMapper.builder().build().valueToTree(payload);
        }
    }

    private static final class ConfigurableBrokerDataProvider implements BrokerDataProvider {

        private List<HoldingSnapshot> holdings = List.of();
        private FundsSnapshot funds = new FundsSnapshot(0, 0, "", "stub", Map.of());
        private List<TradeSnapshot> trades = List.of();
        private List<GttOrderSnapshot> gttOrders = List.of();
        private final Map<String, QuoteSnapshot> quotes = new java.util.HashMap<>();
        private final Map<String, List<HistoricalCandle>> histories = new java.util.HashMap<>();
        private final Map<String, List<OptionChainSnapshot>> optionChains = new java.util.HashMap<>();
        private tools.jackson.databind.JsonNode placeGttOrderResponse = JsonMapper.builder().build().valueToTree(Map.of("Success", Map.of("order_id", "GTT-1")));
        private Map<String, String> lastGttBody;

        @Override
        public List<HoldingSnapshot> getPortfolioHoldings() {
            return holdings;
        }

        @Override
        public FundsSnapshot getFunds() {
            return funds;
        }

        @Override
        public QuoteSnapshot getQuote(String stockCode, String exchangeCode, String productType) {
            return quotes.get(stockCode + "|" + exchangeCode);
        }

        @Override
        public Map<String, QuoteSnapshot> getQuotes(List<String> stockCodes, String exchangeCode, String productType) {
            Map<String, QuoteSnapshot> result = new java.util.LinkedHashMap<>();
            for (String stockCode : stockCodes) {
                QuoteSnapshot quote = getQuote(stockCode, exchangeCode, productType);
                if (quote != null) {
                    result.put(stockCode, quote);
                }
            }
            return result;
        }

        @Override
        public List<HistoricalCandle> getHistoricalCharts(String stockCode, String exchangeCode, String productType, String interval, LocalDate fromDate, LocalDate toDate) {
            return histories.getOrDefault(stockCode + "|" + exchangeCode, List.of());
        }

        @Override
        public List<TradeSnapshot> getTrades(LocalDate fromDate, LocalDate toDate) {
            return trades;
        }

        @Override
        public List<GttOrderSnapshot> getGttOrders() {
            return gttOrders;
        }

        @Override
        public List<OptionChainSnapshot> getOptionChain(String stockCode, String expiryDate, String right) {
            return optionChains.getOrDefault(stockCode + "|" + right, List.of());
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
            lastGttBody = body;
            return placeGttOrderResponse;
        }
    }
}

