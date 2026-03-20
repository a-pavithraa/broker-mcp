package com.broker.service;

import com.broker.model.AnalysisModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class OrderManagementServiceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final StockMetadataService stockMetadataService =
            new StockMetadataService(new DefaultResourceLoader(), objectMapper, "classpath:stock-universe.csv");

    @Test
    void buildOrderPreviewPayload_shouldRoundMoneySensitiveFieldsWithDecimalPrecision() {
        OrderManagementService service = new OrderManagementService(
                new PrecisionBrokerDataProvider(),
                stockMetadataService,
                objectMapper,
                true,
                500000,
                Clock.fixed(Instant.parse("2026-03-19T11:00:00Z"), ZoneId.of("Asia/Kolkata")),
                Path.of("logs/test-trade.log")
        );

        Map<String, Object> result = service.buildOrderPreviewPayload(
                "ICICI Bank",
                "buy",
                3,
                "limit",
                0.335,
                "NSE",
                new OrderManagementService.OrderSession(Map.of("status", "OPEN"), true)
        );

        Map<String, Object> orderSummary = cast(result.get("order_summary"));
        Map<String, Object> charges = cast(result.get("charges"));

        assertEquals(1.01, number(orderSummary.get("gross_value")));
        assertEquals(0.67, number(charges.get("total_charges")));
        assertEquals(1.68, number(result.get("net_amount")));
    }

    @Test
    void buildOrderPreviewPayload_shouldUseSingleHoldingBrokerForImplicitSellRouting() {
        RoutingBrokerDataProvider icici = new RoutingBrokerDataProvider("icici");
        RoutingBrokerDataProvider zerodha = new RoutingBrokerDataProvider("zerodha");
        icici.holdings = List.of(new HoldingSnapshot("ICICIBANK", "ICICI Bank", "NSE", 5, 100, 120, 0, 0, "icici", null));

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CompositeBrokerGateway gateway = new CompositeBrokerGateway(
                    icici,
                    zerodha,
                    executor,
                    stockMetadataService,
                    "zerodha",
                    "zerodha"
            );
            OrderManagementService service = new OrderManagementService(
                    gateway,
                    stockMetadataService,
                    objectMapper,
                    true,
                    500000,
                    Clock.fixed(Instant.parse("2026-03-19T11:00:00Z"), ZoneId.of("Asia/Kolkata")),
                    Path.of("logs/test-trade.log")
            );

            service.buildOrderPreviewPayload(
                    "ICICI Bank",
                    "sell",
                    1,
                    "market",
                    null,
                    "NSE",
                    new OrderManagementService.OrderSession(Map.of("status", "OPEN"), true)
            );

            assertEquals(1, icici.previewOrderCalls);
            assertEquals(0, zerodha.previewOrderCalls);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void buildOrderPreviewPayload_shouldRequireExplicitBrokerWhenSellHoldingExistsOnMultipleBrokers() {
        RoutingBrokerDataProvider icici = new RoutingBrokerDataProvider("icici");
        RoutingBrokerDataProvider zerodha = new RoutingBrokerDataProvider("zerodha");
        icici.holdings = List.of(new HoldingSnapshot("ICICIBANK", "ICICI Bank", "NSE", 3, 100, 120, 0, 0, "icici", null));
        zerodha.holdings = List.of(new HoldingSnapshot("ICICIBANK", "ICICI Bank", "NSE", 4, 100, 120, 0, 0, "zerodha", null));

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CompositeBrokerGateway gateway = new CompositeBrokerGateway(
                    icici,
                    zerodha,
                    executor,
                    stockMetadataService,
                    "zerodha",
                    "zerodha"
            );
            OrderManagementService service = new OrderManagementService(
                    gateway,
                    stockMetadataService,
                    objectMapper,
                    true,
                    500000,
                    Clock.fixed(Instant.parse("2026-03-19T11:00:00Z"), ZoneId.of("Asia/Kolkata")),
                    Path.of("logs/test-trade.log")
            );

            RuntimeException error = assertThrows(RuntimeException.class, () -> service.buildOrderPreviewPayload(
                    "ICICI Bank",
                    "sell",
                    1,
                    "market",
                    null,
                    "NSE",
                    new OrderManagementService.OrderSession(Map.of("status", "OPEN"), true)
            ));

            assertTrue(error.getMessage().contains("Specify broker"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void executeTrade_shouldRejectExplicitSellWhenRequestedBrokerHoldingIsInsufficient() {
        RoutingBrokerDataProvider icici = new RoutingBrokerDataProvider("icici");
        RoutingBrokerDataProvider zerodha = new RoutingBrokerDataProvider("zerodha");
        icici.holdings = List.of(new HoldingSnapshot("ICICIBANK", "ICICI Bank", "NSE", 3, 100, 120, 0, 0, "icici", null));
        zerodha.holdings = List.of(new HoldingSnapshot("ICICIBANK", "ICICI Bank", "NSE", 4, 100, 120, 0, 0, "zerodha", null));

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CompositeBrokerGateway gateway = new CompositeBrokerGateway(
                    icici,
                    zerodha,
                    executor,
                    stockMetadataService,
                    "zerodha",
                    "zerodha"
            );
            OrderManagementService service = new OrderManagementService(
                    gateway,
                    stockMetadataService,
                    objectMapper,
                    true,
                    500000,
                    Clock.fixed(Instant.parse("2026-03-19T11:00:00Z"), ZoneId.of("Asia/Kolkata")),
                    Path.of("logs/test-trade.log")
            );

            Map<String, Object> result = service.executeTrade(
                    "ICICI Bank",
                    "sell",
                    5,
                    "market",
                    null,
                    true,
                    "NSE",
                    "icici",
                    new OrderManagementService.OrderSession(Map.of("status", "OPEN"), true)
            );

            assertEquals("REJECTED", result.get("status"));
            assertTrue(String.valueOf(result.get("message")).contains("Insufficient quantity"));
            assertEquals(0, icici.placeOrderCalls);
            assertEquals(0, zerodha.placeOrderCalls);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void buildOrderPreviewPayload_shouldUseDefaultBrokerFundsForImplicitBuyRouting() {
        RoutingBrokerDataProvider icici = new RoutingBrokerDataProvider("icici");
        RoutingBrokerDataProvider zerodha = new RoutingBrokerDataProvider("zerodha");
        icici.funds = new FundsSnapshot(5_000, 5_000, "1234", "icici", Map.of());
        zerodha.funds = new FundsSnapshot(100, 100, "5678", "zerodha", Map.of());

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CompositeBrokerGateway gateway = new CompositeBrokerGateway(
                    icici,
                    zerodha,
                    executor,
                    stockMetadataService,
                    "zerodha",
                    "zerodha"
            );
            OrderManagementService service = new OrderManagementService(
                    gateway,
                    stockMetadataService,
                    objectMapper,
                    true,
                    500000,
                    Clock.fixed(Instant.parse("2026-03-19T11:00:00Z"), ZoneId.of("Asia/Kolkata")),
                    Path.of("logs/test-trade.log")
            );

            Map<String, Object> result = service.buildOrderPreviewPayload(
                    "ICICI Bank",
                    "buy",
                    1,
                    "market",
                    null,
                    "NSE",
                    new OrderManagementService.OrderSession(Map.of("status", "OPEN"), true)
            );

            Map<String, Object> validation = cast(result.get("validation"));
            assertEquals(false, validation.get("sufficient_funds"));
            assertEquals(0, icici.previewOrderCalls);
            assertEquals(1, zerodha.previewOrderCalls);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void executeTrade_shouldRejectImplicitBuyWhenDefaultBrokerFundsAreInsufficient() {
        RoutingBrokerDataProvider icici = new RoutingBrokerDataProvider("icici");
        RoutingBrokerDataProvider zerodha = new RoutingBrokerDataProvider("zerodha");
        icici.funds = new FundsSnapshot(5_000, 5_000, "1234", "icici", Map.of());
        zerodha.funds = new FundsSnapshot(100, 100, "5678", "zerodha", Map.of());

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CompositeBrokerGateway gateway = new CompositeBrokerGateway(
                    icici,
                    zerodha,
                    executor,
                    stockMetadataService,
                    "zerodha",
                    "zerodha"
            );
            OrderManagementService service = new OrderManagementService(
                    gateway,
                    stockMetadataService,
                    objectMapper,
                    true,
                    500000,
                    Clock.fixed(Instant.parse("2026-03-19T11:00:00Z"), ZoneId.of("Asia/Kolkata")),
                    Path.of("logs/test-trade.log")
            );

            Map<String, Object> result = service.executeTrade(
                    "ICICI Bank",
                    "buy",
                    1,
                    "market",
                    null,
                    true,
                    "NSE",
                    null,
                    new OrderManagementService.OrderSession(Map.of("status", "OPEN"), true)
            );

            assertEquals("REJECTED", result.get("status"));
            assertTrue(String.valueOf(result.get("message")).contains("Insufficient available funds"));
            assertEquals(0, icici.placeOrderCalls);
            assertEquals(0, zerodha.placeOrderCalls);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void executeTrade_shouldAllowExplicitBuyWhenRequestedBrokerFundsAreSufficient() {
        RoutingBrokerDataProvider icici = new RoutingBrokerDataProvider("icici");
        RoutingBrokerDataProvider zerodha = new RoutingBrokerDataProvider("zerodha");
        icici.funds = new FundsSnapshot(5_000, 5_000, "1234", "icici", Map.of());
        zerodha.funds = new FundsSnapshot(100, 100, "5678", "zerodha", Map.of());

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CompositeBrokerGateway gateway = new CompositeBrokerGateway(
                    icici,
                    zerodha,
                    executor,
                    stockMetadataService,
                    "zerodha",
                    "zerodha"
            );
            OrderManagementService service = new OrderManagementService(
                    gateway,
                    stockMetadataService,
                    objectMapper,
                    true,
                    500000,
                    Clock.fixed(Instant.parse("2026-03-19T11:00:00Z"), ZoneId.of("Asia/Kolkata")),
                    Path.of("logs/test-trade.log")
            );

            Map<String, Object> result = service.executeTrade(
                    "ICICI Bank",
                    "buy",
                    1,
                    "market",
                    null,
                    true,
                    "NSE",
                    "icici",
                    new OrderManagementService.OrderSession(Map.of("status", "OPEN"), true)
            );

            assertEquals("PLACED", result.get("status"));
            assertEquals(1, icici.placeOrderCalls);
            assertEquals(0, zerodha.placeOrderCalls);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void buildOrderPreviewPayload_shouldUseDefaultOrderBrokerQuoteForImplicitBuy() {
        RoutingBrokerDataProvider icici = new RoutingBrokerDataProvider("icici");
        RoutingBrokerDataProvider zerodha = new RoutingBrokerDataProvider("zerodha");
        icici.quotePrice = 210;
        zerodha.quotePrice = 120;

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CompositeBrokerGateway gateway = new CompositeBrokerGateway(
                    icici,
                    zerodha,
                    executor,
                    stockMetadataService,
                    "icici",
                    "zerodha"
            );
            OrderManagementService service = new OrderManagementService(
                    gateway,
                    stockMetadataService,
                    objectMapper,
                    true,
                    500000,
                    Clock.fixed(Instant.parse("2026-03-19T11:00:00Z"), ZoneId.of("Asia/Kolkata")),
                    Path.of("logs/test-trade.log")
            );

            Map<String, Object> result = service.buildOrderPreviewPayload(
                    "ICICI Bank",
                    "buy",
                    1,
                    "market",
                    null,
                    "NSE",
                    new OrderManagementService.OrderSession(Map.of("status", "OPEN"), true)
            );

            Map<String, Object> orderSummary = cast(result.get("order_summary"));
            assertEquals(120.0, number(orderSummary.get("price")));
            assertEquals(0, icici.directQuoteCalls);
            assertEquals(1, zerodha.directQuoteCalls);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void buildOrderPreviewPayload_shouldUseResolvedSellBrokerQuoteInsteadOfPreferredDataBroker() {
        RoutingBrokerDataProvider icici = new RoutingBrokerDataProvider("icici");
        RoutingBrokerDataProvider zerodha = new RoutingBrokerDataProvider("zerodha");
        icici.holdings = List.of(new HoldingSnapshot("ICICIBANK", "ICICI Bank", "NSE", 5, 100, 120, 0, 0, "icici", null));
        icici.quotePrice = 120;
        zerodha.quotePrice = 210;

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CompositeBrokerGateway gateway = new CompositeBrokerGateway(
                    icici,
                    zerodha,
                    executor,
                    stockMetadataService,
                    "zerodha",
                    "zerodha"
            );
            OrderManagementService service = new OrderManagementService(
                    gateway,
                    stockMetadataService,
                    objectMapper,
                    true,
                    500000,
                    Clock.fixed(Instant.parse("2026-03-19T11:00:00Z"), ZoneId.of("Asia/Kolkata")),
                    Path.of("logs/test-trade.log")
            );

            Map<String, Object> result = service.buildOrderPreviewPayload(
                    "ICICI Bank",
                    "sell",
                    1,
                    "market",
                    null,
                    "NSE",
                    new OrderManagementService.OrderSession(Map.of("status", "OPEN"), true)
            );

            Map<String, Object> orderSummary = cast(result.get("order_summary"));
            assertEquals(120.0, number(orderSummary.get("price")));
            assertEquals(1, icici.directQuoteCalls);
            assertEquals(0, zerodha.directQuoteCalls);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void buildOrderPreviewPayload_shouldIncludeAveragePriceForExplicitZerodhaSellPreview() {
        RoutingBrokerDataProvider icici = new RoutingBrokerDataProvider("icici");
        RoutingBrokerDataProvider zerodha = new RoutingBrokerDataProvider("zerodha");
        zerodha.holdings = List.of(new HoldingSnapshot("ICICIBANK", "ICICI Bank", "NSE", 5, 123.45, 120, 0, 0, "zerodha", null));

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CompositeBrokerGateway gateway = new CompositeBrokerGateway(
                    icici,
                    zerodha,
                    executor,
                    stockMetadataService,
                    "zerodha",
                    "zerodha"
            );
            OrderManagementService service = new OrderManagementService(
                    gateway,
                    stockMetadataService,
                    objectMapper,
                    true,
                    500000,
                    Clock.fixed(Instant.parse("2026-03-19T11:00:00Z"), ZoneId.of("Asia/Kolkata")),
                    Path.of("logs/test-trade.log")
            );

            service.buildOrderPreviewPayload(
                    "ICICI Bank",
                    "sell",
                    1,
                    "market",
                    null,
                    "NSE",
                    "zerodha",
                    new OrderManagementService.OrderSession(Map.of("status", "OPEN"), true)
            );

            assertEquals("123.45", zerodha.lastPreviewOrderBody.get("average_price"));
        } finally {
            executor.shutdownNow();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    private double number(Object value) {
        return ((Number) value).doubleValue();
    }

    private final class PrecisionBrokerDataProvider implements BrokerDataProvider {

        @Override
        public List<HoldingSnapshot> getPortfolioHoldings() {
            return List.of(new HoldingSnapshot("ICICIBANK", "ICICI Bank", "NSE", 10, 0.10, 0.335, 0, 0, "stub", null));
        }

        @Override
        public FundsSnapshot getFunds() {
            return new FundsSnapshot(1000, 1000, "1234", "stub", Map.of());
        }

        @Override
        public Map<String, QuoteSnapshot> getQuotes(List<String> stockCodes, String exchangeCode, String productType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public QuoteSnapshot getQuote(String stockCode, String exchangeCode, String productType) {
            return new QuoteSnapshot(stockCode, exchangeCode, 0.335, 0.33, 0.33, 0.34, 0.32, 1000, 0.33, 0.34, ZonedDateTime.now());
        }

        @Override
        public List<HistoricalCandle> getHistoricalCharts(String stockCode, String exchangeCode, String productType, String interval, LocalDate fromDate, LocalDate toDate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TradeSnapshot> getTrades(LocalDate fromDate, LocalDate toDate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<GttOrderSnapshot> getGttOrders() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<OptionChainSnapshot> getOptionChain(String stockCode, String expiryDate, String right) {
            throw new UnsupportedOperationException();
        }

        @Override
        public tools.jackson.databind.JsonNode previewOrder(Map<String, String> body) {
            return objectMapper.valueToTree(Map.of(
                    "Success", Map.of(
                            "brokerage", 0.335,
                            "exchange_turnover_charges", 0,
                            "stamp_duty", 0,
                            "stt", 0.335,
                            "sebi_charges", 0,
                            "gst", 0
                    )
            ));
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

    private final class RoutingBrokerDataProvider implements BrokerDataProvider {

        private final String broker;
        private List<HoldingSnapshot> holdings = List.of();
        private FundsSnapshot funds;
        private double quotePrice = 120;
        private int directQuoteCalls;
        private int previewOrderCalls;
        private int placeOrderCalls;
        private Map<String, String> lastPreviewOrderBody = Map.of();

        private RoutingBrokerDataProvider(String broker) {
            this.broker = broker;
            this.funds = new FundsSnapshot(1000, 1000, "1234", broker, Map.of());
        }

        @Override
        public List<HoldingSnapshot> getPortfolioHoldings() {
            return holdings;
        }

        @Override
        public FundsSnapshot getFunds() {
            return funds;
        }

        @Override
        public Map<String, QuoteSnapshot> getQuotes(List<String> stockCodes, String exchangeCode, String productType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public QuoteSnapshot getQuote(String stockCode, String exchangeCode, String productType) {
            directQuoteCalls++;
            return new QuoteSnapshot(stockCode, exchangeCode, quotePrice, quotePrice - 2, quotePrice - 1, quotePrice + 1, quotePrice - 3, 1000, quotePrice - 1, quotePrice, ZonedDateTime.now());
        }

        @Override
        public List<HistoricalCandle> getHistoricalCharts(String stockCode, String exchangeCode, String productType, String interval, LocalDate fromDate, LocalDate toDate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TradeSnapshot> getTrades(LocalDate fromDate, LocalDate toDate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<GttOrderSnapshot> getGttOrders() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<OptionChainSnapshot> getOptionChain(String stockCode, String expiryDate, String right) {
            throw new UnsupportedOperationException();
        }

        @Override
        public tools.jackson.databind.JsonNode previewOrder(Map<String, String> body) {
            previewOrderCalls++;
            lastPreviewOrderBody = Map.copyOf(body);
            return objectMapper.valueToTree(Map.of(
                    "Success", Map.of(
                            "brokerage", 1,
                            "exchange_turnover_charges", 1,
                            "stamp_duty", 1,
                            "stt", 1,
                            "sebi_charges", 1,
                            "gst", 1
                    )
            ));
        }

        @Override
        public tools.jackson.databind.JsonNode placeOrder(Map<String, String> body) {
            placeOrderCalls++;
            return objectMapper.valueToTree(Map.of("Success", Map.of("order_id", broker + "-1", "message", "Placed")));
        }

        @Override
        public tools.jackson.databind.JsonNode getOrderDetail(String exchangeCode, String orderId) {
            return objectMapper.valueToTree(Map.of("Success", Map.of("message", "Placed")));
        }

        @Override
        public tools.jackson.databind.JsonNode placeGttOrder(Map<String, String> body) {
            throw new UnsupportedOperationException();
        }
    }
}
