package com.broker.gateway.icici;

import com.broker.model.AnalysisModels.*;
import com.broker.reference.StockMetadataService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BreezeGatewayServiceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final StockMetadataService stockMetadataService = new StockMetadataService(new DefaultResourceLoader(), objectMapper, "classpath:stock-universe.csv");

    @Test
    void getPortfolioHoldings_shouldNormalizeSymbolsAndPopulateIsin() {
        RecordingBreezeApiClient apiClient = new RecordingBreezeApiClient(objectMapper);
        apiClient.getResponses.put("/portfolioholdings", json(Map.of(
                "Success", List.of(Map.of(
                        "stock_code", "TATPOW",
                        "stock_name", "Tata Power",
                        "exchange_code", "NSE",
                        "quantity", "10",
                        "average_price", "100",
                        "current_market_price", "120",
                        "booked_profit_loss", "50",
                        "change_percentage", "1.7"
                ))
        )));
        BreezeGatewayService service = new BreezeGatewayService(apiClient, stockMetadataService, List.of("NSE"));

        List<HoldingSnapshot> holdings = service.getPortfolioHoldings();

        assertEquals(1, holdings.size());
        assertEquals("TATAPOWER", holdings.getFirst().stockCode());
        assertEquals("icici", holdings.getFirst().broker());
        assertEquals("INE245A01021", holdings.getFirst().isin());
    }

    @Test
    void getQuote_shouldTranslateCanonicalSymbolBeforeCallingIcici() {
        RecordingBreezeApiClient apiClient = new RecordingBreezeApiClient(objectMapper);
        apiClient.getResponses.put("/quotes", json(Map.of(
                "Success", List.of(Map.ofEntries(
                        Map.entry("stock_code", "TATPOW"),
                        Map.entry("exchange_code", "NSE"),
                        Map.entry("ltp", "120"),
                        Map.entry("previous_close", "118"),
                        Map.entry("open", "119"),
                        Map.entry("high", "121"),
                        Map.entry("low", "117"),
                        Map.entry("total_quantity_traded", "10000"),
                        Map.entry("best_bid_price", "119"),
                        Map.entry("best_offer_price", "120"),
                        Map.entry("ltt", "2026-03-16T05:45:00Z")
                ))
        )));
        BreezeGatewayService service = new BreezeGatewayService(apiClient, stockMetadataService, List.of("NSE"));

        QuoteSnapshot quote = service.getQuote("TATAPOWER", "NSE", "cash");

        assertEquals("TATAPOWER", quote.stockCode());
        assertEquals("TATPOW", apiClient.lastGetBody.get("stock_code"));
        assertEquals("NSE", apiClient.lastGetBody.get("exchange_code"));
        assertEquals("cash", apiClient.lastGetBody.get("product_type"));
    }

    @Test
    void getTradesAndGttOrders_shouldNormalizeBrokerSnapshots() {
        RecordingBreezeApiClient apiClient = new RecordingBreezeApiClient(objectMapper);
        apiClient.getResponses.put("/trades", json(Map.of(
                "Success", List.of(Map.of(
                        "stock_code", "ICIBAN",
                        "action", "BUY",
                        "quantity", "5",
                        "average_cost", "1000",
                        "trade_date", "2026-03-15"
                ))
        )));
        apiClient.getResponses.put("/gttorder", json(Map.of(
                "Success", List.of(Map.of(
                        "fresh_order_id", "GTT-1",
                        "stock_code", "ICIBAN",
                        "quantity", "5"
                ))
        )));
        BreezeGatewayService service = new BreezeGatewayService(apiClient, stockMetadataService, List.of("NSE"));

        List<TradeSnapshot> trades = service.getTrades(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 16));
        List<GttOrderSnapshot> orders = service.getGttOrders();

        assertEquals("ICICIBANK", trades.getFirst().stockCode());
        assertEquals("icici", trades.getFirst().broker());
        assertEquals("ICICIBANK", orders.getFirst().stockCode());
        assertEquals("icici", orders.getFirst().broker());
    }

    @Test
    void fundsAndOrderEndpoints_shouldExposeDetailsAndTranslateCanonicalBodies() {
        RecordingBreezeApiClient apiClient = new RecordingBreezeApiClient(objectMapper);
        apiClient.getResponses.put("/funds", json(Map.of(
                "Success", Map.of(
                        "bank_account", "1234",
                        "total_balance", "12500",
                        "unallocated_balance", "3000",
                        "allocated_equity", "5000",
                        "allocated_fno", "1500",
                        "block_by_trade_equity", "250"
                )
        )));
        apiClient.getResponses.put("/preview_order", json(Map.of("Success", Map.of("brokerage", 10))));
        apiClient.postResponses.put("/order", json(Map.of("Success", Map.of("order_id", "ORD-1"))));
        apiClient.postResponses.put("/gttorder", json(Map.of("Success", Map.of("order_id", "GTT-1"))));
        BreezeGatewayService service = new BreezeGatewayService(apiClient, stockMetadataService, List.of("NSE"));

        FundsSnapshot funds = service.getFunds();
        service.previewOrder(Map.of(
                "symbol", "ICICIBANK",
                "exchange", "NSE",
                "product", "cash",
                "action", "buy",
                "order_type", "market",
                "quantity", "5",
                "price", "200",
                "specialflag", "N"
        ));
        assertEquals("ICIBAN", apiClient.lastGetBody.get("stock_code"));
        assertEquals("NSE", apiClient.lastGetBody.get("exchange_code"));
        assertFalse(apiClient.lastGetBody.containsKey("symbol"));

        service.placeOrder(Map.of(
                "symbol", "ICICIBANK",
                "exchange", "NSE",
                "product", "cash",
                "action", "buy",
                "order_type", "market",
                "quantity", "5",
                "price", "200",
                "validity", "day"
        ));
        assertEquals("ICIBAN", apiClient.lastPostBody.get("stock_code"));
        assertEquals("cash", apiClient.lastPostBody.get("product"));

        service.placeGttOrder(Map.of(
                "symbol", "ICICIBANK",
                "exchange", "NSE",
                "product", "cash",
                "action", "sell",
                "order_type", "market",
                "quantity", "5",
                "price", "0",
                "trigger_price", "180",
                "limit_price", "180"
        ));

        assertEquals(12500.0, funds.totalBalance());
        assertEquals("icici", funds.broker());
        assertEquals("5000", funds.details().get("allocated_equity"));
        assertEquals("1500", funds.details().get("allocated_fno"));
        assertEquals("250", funds.details().get("block_by_trade_equity"));
        assertEquals("ICIBAN", apiClient.lastPostBody.get("stock_code"));
        assertEquals("cash", apiClient.lastPostBody.get("product_type"));
        assertFalse(apiClient.lastPostBody.containsKey("symbol"));
    }

    @Test
    void getFunds_shouldLoadCacheOnceUnderConcurrentAccess() throws Exception {
        SlowFundsBreezeApiClient apiClient = new SlowFundsBreezeApiClient(objectMapper, json(Map.of(
                "Success", Map.of(
                        "bank_account", "1234",
                        "total_balance", "12500",
                        "unallocated_balance", "3000"
                )
        )));
        BreezeGatewayService service = new BreezeGatewayService(apiClient, stockMetadataService, List.of("NSE"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<FundsSnapshot>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return service.getFunds();
                }));
            }

            start.countDown();
            for (Future<FundsSnapshot> future : futures) {
                assertEquals(12500.0, future.get(5, TimeUnit.SECONDS).totalBalance());
            }

            assertEquals(1, apiClient.fundsCalls.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private JsonNode json(Map<String, Object> payload) {
        return objectMapper.valueToTree(payload);
    }

    private static final class RecordingBreezeApiClient extends BreezeApiClient {

        private final Map<String, JsonNode> getResponses = new HashMap<>();
        private final Map<String, JsonNode> postResponses = new HashMap<>();
        private Map<String, String> lastGetBody = Map.of();
        private Map<String, String> lastPostBody = Map.of();

        private RecordingBreezeApiClient(ObjectMapper objectMapper) {
            super(new com.broker.config.BreezeConfig("https://example.test", null, null),
                    new BreezeSessionManager(),
                    new BreezeChecksumGenerator(),
                    objectMapper,
                    RestClient.builder());
        }

        @Override
        public JsonNode get(String endpoint, Map<String, String> params) {
            lastGetBody = params;
            return getResponses.getOrDefault(endpoint, JsonMapper.builder().build().createObjectNode());
        }

        @Override
        public JsonNode post(String endpoint, Object body) {
            lastPostBody = (Map<String, String>) body;
            return postResponses.getOrDefault(endpoint, JsonMapper.builder().build().createObjectNode());
        }
    }

    private static final class SlowFundsBreezeApiClient extends BreezeApiClient {
        private final JsonNode fundsResponse;
        private final AtomicInteger fundsCalls = new AtomicInteger();

        private SlowFundsBreezeApiClient(ObjectMapper objectMapper, JsonNode fundsResponse) {
            super(new com.broker.config.BreezeConfig("https://example.test", null, null),
                    new BreezeSessionManager(),
                    new BreezeChecksumGenerator(),
                    objectMapper,
                    RestClient.builder());
            this.fundsResponse = fundsResponse;
        }

        @Override
        public JsonNode get(String endpoint, Map<String, String> params) {
            if ("/funds".equals(endpoint)) {
                fundsCalls.incrementAndGet();
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return fundsResponse;
            }
            return JsonMapper.builder().build().createObjectNode();
        }
    }
}
