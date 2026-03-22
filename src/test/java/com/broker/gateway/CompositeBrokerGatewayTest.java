package com.broker.gateway;

import com.broker.exception.BrokerCapabilityException;
import com.broker.model.AnalysisModels.*;
import com.broker.reference.StockMetadataService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompositeBrokerGatewayTest {

    private final List<ExecutorService> executors = new ArrayList<>();
    private StockMetadataService stockMetadataService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        stockMetadataService = new StockMetadataService(new DefaultResourceLoader(), objectMapper, "classpath:stock-universe.csv");
    }

    @AfterEach
    void tearDown() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void getPortfolioHoldings_shouldMergeByIsinAndPreserveSingles() {
        StubBrokerDataProvider icici = new StubBrokerDataProvider("icici");
        StubBrokerDataProvider zerodha = new StubBrokerDataProvider("zerodha");
        icici.holdings = List.of(
                new HoldingSnapshot("INFY", "Infosys", "NSE", 10, 100, 120, 50, 2, "icici", "INE009A01021"),
                new HoldingSnapshot("TCS", "TCS", "NSE", 2, 4000, 4200, 100, 1, "icici", "INE467B01029")
        );
        zerodha.holdings = List.of(
                new HoldingSnapshot("INFY", "Infosys", "NSE", 5, 110, 121, 25, 3, "zerodha", "INE009A01021")
        );

        CompositeBrokerGateway gateway = new CompositeBrokerGateway(icici, zerodha, executor(), stockMetadataService, "zerodha", "zerodha");

        List<HoldingSnapshot> holdings = gateway.getPortfolioHoldings();

        assertEquals(2, holdings.size());
        HoldingSnapshot infy = holdings.stream().filter(item -> "INFY".equals(item.stockCode())).findFirst().orElseThrow();
        assertEquals("composite", infy.broker());
        assertEquals(15.0, infy.quantity(), 0.01);
        assertEquals(103.33, round2(infy.averagePrice()), 0.01);
        assertEquals(121.0, infy.currentMarketPrice(), 0.01);
        assertEquals(75.0, infy.bookedProfitLoss(), 0.01);

        HoldingSnapshot tcs = holdings.stream().filter(item -> "TCS".equals(item.stockCode())).findFirst().orElseThrow();
        assertEquals("icici", tcs.broker());
    }

    @Test
    void getTradesAndFunds_shouldReturnAvailableDataWhenOneBrokerFails() {
        StubBrokerDataProvider icici = new StubBrokerDataProvider("icici");
        StubBrokerDataProvider zerodha = new StubBrokerDataProvider("zerodha");
        icici.throwOnTrades = true;
        icici.throwOnFunds = true;
        zerodha.trades = List.of(new TradeSnapshot("INFY", "buy", 2, 1500, LocalDate.of(2026, 3, 16), "zerodha"));
        zerodha.funds = new FundsSnapshot(5000, 2500, "", "zerodha", Map.of());

        CompositeBrokerGateway gateway = new CompositeBrokerGateway(icici, zerodha, executor(), stockMetadataService, "zerodha", "zerodha");

        List<TradeSnapshot> trades = gateway.getTrades(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        List<FundsSnapshot> funds = gateway.getAllFunds();

        assertEquals(1, trades.size());
        assertEquals("zerodha", trades.getFirst().broker());
        assertEquals(1, funds.size());
        assertEquals("zerodha", funds.getFirst().broker());
    }

    @Test
    void marketData_shouldUsePreferredBrokerAndFallbackOnCapabilityFailure() {
        StubBrokerDataProvider icici = new StubBrokerDataProvider("icici");
        StubBrokerDataProvider zerodha = new StubBrokerDataProvider("zerodha");
        zerodha.throwCapabilityOnQuote = true;
        zerodha.quotes.put("INFY", new QuoteSnapshot("INFY", "NSE", 1510, 1490, 1500, 1520, 1495, 1000, 1509, 1511, now()));
        icici.quotes.put("INFY", new QuoteSnapshot("INFY", "NSE", 1500, 1480, 1490, 1510, 1485, 1000, 1499, 1501, now()));
        zerodha.batchQuotes.put("INFY", zerodha.quotes.get("INFY"));

        CompositeBrokerGateway gateway = new CompositeBrokerGateway(icici, zerodha, executor(), stockMetadataService, "zerodha", "zerodha");

        QuoteSnapshot quote = gateway.getQuote("INFY", "NSE", "cash");
        Map<String, QuoteSnapshot> batch = gateway.getQuotes(List.of("INFY"), "NSE", "cash");

        assertEquals(1500.0, quote.ltp(), 0.01);
        assertEquals(1, zerodha.quoteCalls);
        assertEquals(1, icici.quoteCalls);
        assertEquals(1510.0, batch.get("INFY").ltp(), 0.01);
        assertEquals(1, zerodha.batchQuoteCalls);
        assertEquals(0, icici.batchQuoteCalls);
    }

    @Test
    void orderRouting_shouldUseDefaultAndExplicitBrokerAndRememberOrderDetailRouting() {
        StubBrokerDataProvider icici = new StubBrokerDataProvider("icici");
        StubBrokerDataProvider zerodha = new StubBrokerDataProvider("zerodha");
        icici.placeOrderResponse = success("ICICI-1");
        zerodha.placeOrderResponse = success("ZERODHA-1");
        icici.orderDetailResponse = detail("ICICI complete");
        zerodha.orderDetailResponse = detail("ZERODHA complete");

        CompositeBrokerGateway gateway = new CompositeBrokerGateway(icici, zerodha, executor(), stockMetadataService, "zerodha", "zerodha");

        JsonNode defaultOrder = gateway.placeOrder(Map.of("symbol", "INFY", "quantity", "1"));
        JsonNode explicitOrder = gateway.placeOrder(Map.of("symbol", "INFY", "quantity", "1", "broker", "icici"));
        JsonNode defaultDetail = gateway.getOrderDetail("NSE", "ZERODHA-1");
        JsonNode explicitDetail = gateway.getOrderDetail("NSE", "ICICI-1");

        assertEquals(1, zerodha.placeOrderCalls);
        assertEquals(1, icici.placeOrderCalls);
        assertEquals("ZERODHA-1", defaultOrder.path("Success").path("order_id").asText());
        assertEquals("ICICI-1", explicitOrder.path("Success").path("order_id").asText());
        assertEquals("ZERODHA complete", defaultDetail.path("Success").path("message").asText());
        assertEquals("ICICI complete", explicitDetail.path("Success").path("message").asText());
    }

    @Test
    void getPortfolioHoldings_shouldMergeWhenSymbolsDifferButMetadataMatches() {
        StubBrokerDataProvider icici = new StubBrokerDataProvider("icici");
        StubBrokerDataProvider zerodha = new StubBrokerDataProvider("zerodha");
        icici.holdings = List.of(
                new HoldingSnapshot("INFTEC", "Infosys", "NSE", 10, 100, 120, 50, 2, "icici", null)
        );
        zerodha.holdings = List.of(
                new HoldingSnapshot("INFY", "Infosys", "NSE", 5, 110, 121, 25, 3, "zerodha", null)
        );

        CompositeBrokerGateway gateway = new CompositeBrokerGateway(icici, zerodha, executor(), stockMetadataService, "zerodha", "zerodha");

        List<HoldingSnapshot> holdings = gateway.getPortfolioHoldings();

        assertEquals(1, holdings.size());
        HoldingSnapshot infy = holdings.getFirst();
        assertEquals("composite", infy.broker());
        assertEquals(15.0, infy.quantity(), 0.01);
    }

    private ExecutorService executor() {
        ExecutorService executorService = Executors.newFixedThreadPool(4);
        executors.add(executorService);
        return executorService;
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    private JsonNode success(String orderId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("Success", Map.of("order_id", orderId));
        response.put("Error", null);
        return JsonMapper.builder().build().valueToTree(response);
    }

    private JsonNode detail(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("Success", Map.of("message", message));
        response.put("Error", null);
        return JsonMapper.builder().build().valueToTree(response);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static final class StubBrokerDataProvider implements BrokerDataProvider {

        private final String broker;
        private List<HoldingSnapshot> holdings = List.of();
        private List<TradeSnapshot> trades = List.of();
        private FundsSnapshot funds = new FundsSnapshot(0, 0, "", "stub", Map.of());
        private final Map<String, QuoteSnapshot> quotes = new java.util.HashMap<>();
        private final Map<String, QuoteSnapshot> batchQuotes = new java.util.HashMap<>();
        private JsonNode placeOrderResponse = jsonResponse("order_id", "ORDER");
        private JsonNode orderDetailResponse = jsonResponse("message", "ok");
        private boolean throwOnTrades;
        private boolean throwOnFunds;
        private boolean throwCapabilityOnQuote;
        private int quoteCalls;
        private int batchQuoteCalls;
        private int placeOrderCalls;

        private StubBrokerDataProvider(String broker) {
            this.broker = broker;
        }

        private static JsonNode jsonResponse(String key, String value) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("Success", Map.of(key, value));
            response.put("Error", null);
            return JsonMapper.builder().build().valueToTree(response);
        }

        @Override
        public List<HoldingSnapshot> getPortfolioHoldings() {
            return holdings;
        }

        @Override
        public FundsSnapshot getFunds() {
            if (throwOnFunds) {
                throw new RuntimeException("funds unavailable");
            }
            return funds;
        }

        @Override
        public QuoteSnapshot getQuote(String stockCode, String exchangeCode, String productType) {
            quoteCalls++;
            if (throwCapabilityOnQuote) {
                throw new BrokerCapabilityException("unavailable");
            }
            return quotes.get(stockCode);
        }

        @Override
        public Map<String, QuoteSnapshot> getQuotes(List<String> stockCodes, String exchangeCode, String productType) {
            batchQuoteCalls++;
            return new LinkedHashMap<>(batchQuotes);
        }

        @Override
        public List<HistoricalCandle> getHistoricalCharts(String stockCode, String exchangeCode, String productType, String interval, LocalDate fromDate, LocalDate toDate) {
            return List.of();
        }

        @Override
        public List<TradeSnapshot> getTrades(LocalDate fromDate, LocalDate toDate) {
            if (throwOnTrades) {
                throw new RuntimeException("trades unavailable");
            }
            return trades;
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
        public JsonNode previewOrder(Map<String, String> body) {
            return placeOrderResponse;
        }

        @Override
        public JsonNode placeOrder(Map<String, String> body) {
            placeOrderCalls++;
            return placeOrderResponse;
        }

        @Override
        public JsonNode getOrderDetail(String exchangeCode, String orderId) {
            return orderDetailResponse;
        }

        @Override
        public JsonNode placeGttOrder(Map<String, String> body) {
            return placeOrderResponse;
        }
    }
}
