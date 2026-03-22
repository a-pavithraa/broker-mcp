package com.broker.gateway.zerodha;

import com.broker.config.ZerodhaTradebookProperties;
import com.broker.exception.BrokerCapabilityException;
import com.broker.gateway.RateLimiter;
import com.broker.model.AnalysisModels;
import com.broker.model.AnalysisModels.FundsSnapshot;
import com.broker.model.AnalysisModels.GttOrderSnapshot;
import com.broker.model.AnalysisModels.HoldingSnapshot;
import com.broker.model.AnalysisModels.TradeSnapshot;
import com.broker.reference.StockMetadataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ZerodhaGatewayServiceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void getPortfolioHoldingsFundsTradesAndGtt_shouldNormalizeFreeTierResponses() {
        RecordingZerodhaApiClient apiClient = new RecordingZerodhaApiClient(objectMapper);
        ZerodhaInstrumentCache cache = emptyCache();
        apiClient.getResponses.put("/portfolio/holdings", json(Map.of(
                "status", "success",
                "data", List.of(Map.of(
                        "tradingsymbol", "INFY",
                        "exchange", "NSE",
                        "quantity", 4,
                        "average_price", 1500.0,
                        "last_price", 1600.0,
                        "pnl", 400.0,
                        "day_change_percentage", 1.5,
                        "isin", "INE009A01021"
                ))
        )));
        apiClient.getResponses.put("/user/margins", json(Map.of(
                "status", "success",
                "data", Map.of(
                        "equity", Map.of(
                                "net", 12000.0,
                                "available", Map.of("live_balance", 5000.0, "collateral", 800.0),
                                "utilised", Map.of("span", 200.0, "exposure", 150.0)
                        ),
                        "commodity", Map.of(
                                "net", 3000.0,
                                "available", Map.of("live_balance", 1000.0, "collateral", 200.0),
                                "utilised", Map.of("span", 50.0, "exposure", 20.0)
                        )
                )
        )));
        apiClient.getResponses.put("/trades", json(Map.of(
                "status", "success",
                "data", List.of(
                        Map.of(
                                "tradingsymbol", "INFY",
                                "transaction_type", "BUY",
                                "quantity", 2,
                                "average_price", 1490.0,
                                "fill_timestamp", "2026-03-16 10:15:00+05:30"
                        ),
                        Map.of(
                                "tradingsymbol", "INFY",
                                "transaction_type", "SELL",
                                "quantity", 1,
                                "average_price", 1510.0,
                                "fill_timestamp", "2026-02-10 10:15:00+05:30"
                        )
                )
        )));
        apiClient.getResponses.put("/gtt/triggers", json(Map.of(
                "status", "success",
                "data", List.of(Map.of(
                        "id", 42,
                        "condition", Map.of("tradingsymbol", "INFY"),
                        "orders", List.of(Map.of("quantity", 4))
                ))
        )));

        CountingLimiter quotes = new CountingLimiter();
        CountingLimiter historical = new CountingLimiter();
        CountingLimiter orders = new CountingLimiter();
        CountingLimiter general = new CountingLimiter();
        ZerodhaGatewayService service = new ZerodhaGatewayService(apiClient, cache, "free", quotes, historical, orders, general,
                null, Clock.fixed(Instant.parse("2026-03-16T04:00:00Z"), ZoneId.of("Asia/Kolkata")));

        List<HoldingSnapshot> holdings = service.getPortfolioHoldings();
        FundsSnapshot funds = service.getFunds();
        List<TradeSnapshot> trades = service.getTrades(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 20));
        List<GttOrderSnapshot> gttOrders = service.getGttOrders();

        assertEquals(1, holdings.size());
        assertEquals("INFY", holdings.getFirst().stockCode());
        assertEquals("zerodha", holdings.getFirst().broker());
        assertEquals("INE009A01021", holdings.getFirst().isin());

        assertEquals(15000.0, funds.totalBalance());
        assertEquals(6000.0, funds.unallocatedBalance());
        assertEquals("5000.0", funds.details().get("equity_available"));
        assertEquals("1000.0", funds.details().get("commodity_available"));
        assertEquals("1000.0", funds.details().get("collateral"));
        assertEquals("250.0", funds.details().get("span_used"));
        assertEquals("170.0", funds.details().get("exposure_used"));

        assertEquals(1, trades.size());
        assertEquals("INFY", trades.getFirst().stockCode());
        assertEquals("buy", trades.getFirst().action());
        assertEquals("zerodha", trades.getFirst().broker());

        assertEquals(1, gttOrders.size());
        assertEquals("INFY", gttOrders.getFirst().stockCode());
        assertEquals("zerodha", gttOrders.getFirst().broker());
        assertEquals(4.0, gttOrders.getFirst().quantity());

        assertEquals(4, general.calls);
        assertEquals(0, orders.calls);
    }

    @Test
    void previewAndPlaceOrder_shouldTranslateCanonicalBodies() {
        RecordingZerodhaApiClient apiClient = new RecordingZerodhaApiClient(objectMapper);
        ZerodhaInstrumentCache cache = emptyCache();
        apiClient.postJsonResponses.put("/charges/orders", json(Map.of(
                "status", "success",
                "data", List.of(Map.of(
                        "charges", Map.of(
                                "brokerage", 0.0,
                                "transaction_tax", 4.0,
                                "exchange_turnover_charge", 2.0,
                                "stamp_duty", 1.0,
                                "sebi_turnover_charge", 0.5,
                                "gst", Map.of("total", 1.5)
                        )
                ))
        )));
        apiClient.postFormResponses.put("/orders/regular", json(Map.of(
                "status", "success",
                "message", "Order placed",
                "data", Map.of("order_id", "2403160001")
        )));
        apiClient.getResponses.put("/orders/2403160001", json(Map.of(
                "status", "success",
                "data", List.of(Map.of("status", "COMPLETE", "status_message", "Filled"))
        )));

        CountingLimiter limiter = new CountingLimiter();
        ZerodhaGatewayService service = new ZerodhaGatewayService(apiClient, cache, "free",
                new CountingLimiter(), new CountingLimiter(), limiter, new CountingLimiter());

        JsonNode preview = service.previewOrder(Map.of(
                "symbol", "INFY",
                "exchange", "NSE",
                "product", "cash",
                "action", "buy",
                "order_type", "market",
                "quantity", "5",
                "price", "1600"
        ));
        JsonNode placed = service.placeOrder(Map.of(
                "symbol", "INFY",
                "exchange", "NSE",
                "product", "cash",
                "action", "buy",
                "order_type", "market",
                "quantity", "5",
                "price", "1600",
                "validity", "day"
        ));
        JsonNode detail = service.getOrderDetail("NSE", "2403160001");

        assertEquals("INFY", apiClient.lastChargesRequest.getFirst().get("tradingsymbol"));
        assertEquals("CNC", String.valueOf(apiClient.lastChargesRequest.getFirst().get("product")));
        assertEquals("BUY", String.valueOf(apiClient.lastChargesRequest.getFirst().get("transaction_type")));
        assertEquals("INFY", apiClient.lastFormBody.get("tradingsymbol"));
        assertEquals("CNC", apiClient.lastFormBody.get("product"));
        assertEquals("BUY", apiClient.lastFormBody.get("transaction_type"));
        assertEquals("2403160001", placed.path("Success").path("order_id").asText());
        assertEquals("Filled", detail.path("Success").path("message").asText());
        assertEquals(2, limiter.calls);
        assertEquals(4.0, preview.path("Success").path("stt").asDouble());
    }

    @Test
    void placeOrder_shouldRejectCashSellWithCdslMessage() {
        RecordingZerodhaApiClient apiClient = new RecordingZerodhaApiClient(objectMapper);
        ZerodhaGatewayService service = new ZerodhaGatewayService(apiClient, emptyCache(), "free",
                new CountingLimiter(), new CountingLimiter(), new CountingLimiter(), new CountingLimiter());

        JsonNode response = service.placeOrder(Map.of(
                "symbol", "INFY",
                "exchange", "NSE",
                "product", "cash",
                "action", "sell",
                "order_type", "market",
                "quantity", "5",
                "price", "0"
        ));

        assertTrue(response.path("Error").asText().contains("CDSL"));
    }

    @Test
    void paidTierMethodsShouldBeBlockedWhenTierIsFree() {
        RecordingZerodhaApiClient apiClient = new RecordingZerodhaApiClient(objectMapper);
        CountingLimiter quotes = new CountingLimiter();
        CountingLimiter historical = new CountingLimiter();
        ZerodhaGatewayService service = new ZerodhaGatewayService(apiClient, emptyCache(), "free",
                quotes, historical, new CountingLimiter(), new CountingLimiter());

        assertThrows(BrokerCapabilityException.class, () -> service.getQuote("INFY", "NSE", "cash"));
        assertThrows(BrokerCapabilityException.class, () -> service.getQuotes(List.of("INFY"), "NSE", "cash"));
        assertThrows(BrokerCapabilityException.class, () -> service.getHistoricalCharts("INFY", "NSE", "cash", "day", LocalDate.now(), LocalDate.now()));
        assertThrows(BrokerCapabilityException.class, () -> service.getOptionChain("NIFTY", "2026-03-19", "call"));
        assertEquals(0, quotes.calls());
        assertEquals(0, historical.calls());
    }

    @Test
    void paidTierMethodsShouldBatchQuotesResolveIndexSymbolsAndParseHistoricalCandles() {
        RecordingZerodhaApiClient apiClient = new RecordingZerodhaApiClient(objectMapper);
        ZerodhaInstrumentCache cache = seededCache();
        apiClient.getResponses.put("/quote", json(Map.of(
                "status", "success",
                "data", Map.of(
                        "NSE:INFY", Map.of(
                                "last_price", 1600.0,
                                "volume", 10000,
                                "timestamp", "2026-03-16T10:15:00Z",
                                "ohlc", Map.of("open", 1590.0, "high", 1610.0, "low", 1585.0, "close", 1588.0),
                                "depth", Map.of(
                                        "buy", List.of(Map.of("price", 1599.0)),
                                        "sell", List.of(Map.of("price", 1601.0))
                                )
                        ),
                        "NSE:NIFTY 50", Map.of(
                                "last_price", 22500.0,
                                "volume", 0,
                                "timestamp", "2026-03-16T10:15:00Z",
                                "ohlc", Map.of("open", 22350.0, "high", 22550.0, "low", 22320.0, "close", 22300.0),
                                "depth", Map.of("buy", List.of(), "sell", List.of())
                        )
                )
        )));
        apiClient.getResponses.put("/instruments/historical/1594/day", json(Map.of(
                "status", "success",
                "data", Map.of(
                        "candles", List.of(
                                List.of("2026-03-14T00:00:00+05:30", 1580.0, 1600.0, 1575.0, 1590.0, 9000.0, 0.0),
                                List.of("2026-03-15T00:00:00+05:30", 1590.0, 1610.0, 1588.0, 1605.0, 9500.0, 0.0)
                        )
                )
        )));

        CountingLimiter quotes = new CountingLimiter();
        CountingLimiter historical = new CountingLimiter();
        ZerodhaGatewayService service = new ZerodhaGatewayService(apiClient, cache, "paid",
                quotes, historical, new CountingLimiter(), new CountingLimiter());

        Map<String, AnalysisModels.QuoteSnapshot> quotesMap =
                service.getQuotes(List.of("INFY", "NIFTY"), "NSE", "cash");
        assertEquals(1, apiClient.quoteCalls);
        assertEquals(List.of("NSE:INFY", "NSE:NIFTY 50"), apiClient.lastQueryParams.get("i"));
        assertEquals(2, quotesMap.size());
        assertEquals(1600.0, quotesMap.get("INFY").ltp());
        assertEquals(22500.0, quotesMap.get("NIFTY").ltp());

        var quote = service.getQuote("INFY", "NSE", "cash");
        var candles = service.getHistoricalCharts("INFY", "NSE", "cash", "day",
                LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 15));

        assertEquals(2, apiClient.quoteCalls);
        assertEquals(1599.0, quote.bestBidPrice());
        assertEquals(2, candles.size());
        assertEquals(1605.0, candles.getLast().close());
        assertEquals("2026-03-14 00:00:00", apiClient.lastQueryParams.get("from").getFirst());
        assertEquals("2026-03-16 00:00:00", apiClient.lastQueryParams.get("to").getFirst());
        assertEquals(2, quotes.calls);
        assertEquals(1, historical.calls);
    }

    @Test
    void zerodhaDateParsing_shouldAcceptLocalTimestampsWithoutOffset() {
        RecordingZerodhaApiClient apiClient = new RecordingZerodhaApiClient(objectMapper);
        ZerodhaInstrumentCache cache = seededCache();
        apiClient.getResponses.put("/quote", json(Map.of(
                "status", "success",
                "data", Map.of(
                        "NSE:INFY", Map.of(
                                "last_price", 1600.0,
                                "volume", 10000,
                                "timestamp", "2026-03-16T17:33:44",
                                "ohlc", Map.of("open", 1590.0, "high", 1610.0, "low", 1585.0, "close", 1588.0),
                                "depth", Map.of(
                                        "buy", List.of(Map.of("price", 1599.0)),
                                        "sell", List.of(Map.of("price", 1601.0))
                                )
                        )
                )
        )));
        apiClient.getResponses.put("/instruments/historical/1594/day", json(Map.of(
                "status", "success",
                "data", Map.of(
                        "candles", List.of(
                                List.of("2026-03-14T00:00:00", 1580.0, 1600.0, 1575.0, 1590.0, 9000.0, 0.0)
                        )
                )
        )));
        apiClient.getResponses.put("/trades", json(Map.of(
                "status", "success",
                "data", List.of(
                        Map.of(
                                "tradingsymbol", "INFY",
                                "transaction_type", "BUY",
                                "quantity", 2,
                                "average_price", 1490.0,
                                "fill_timestamp", "2026-03-16T10:15:00"
                        )
                )
        )));

        ZerodhaGatewayService service = new ZerodhaGatewayService(apiClient, cache, "paid",
                new CountingLimiter(), new CountingLimiter(), new CountingLimiter(), new CountingLimiter(),
                null, Clock.fixed(Instant.parse("2026-03-16T11:00:00Z"), ZoneId.of("Asia/Kolkata")));

        var quote = service.getQuote("INFY", "NSE", "cash");
        var candles = service.getHistoricalCharts("INFY", "NSE", "cash", "day",
                LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 14));
        var trades = service.getTrades(LocalDate.of(2026, 3, 16), LocalDate.of(2026, 3, 16));

        assertEquals("Asia/Kolkata", quote.lastTradeTime().getZone().getId());
        assertEquals(1, candles.size());
        assertEquals("Asia/Kolkata", candles.getFirst().dateTime().getZone().getId());
        assertEquals(1, trades.size());
        assertEquals(LocalDate.of(2026, 3, 16), trades.getFirst().tradeDate());
    }

    @Test
    void getTradesShouldReturnImportedHistoricalTradesAndMergeSameDayApi(@TempDir Path tempDir) throws Exception {
        RecordingZerodhaApiClient apiClient = new RecordingZerodhaApiClient(objectMapper);
        apiClient.getResponses.put("/trades", json(Map.of(
                "status", "success",
                "data", List.of(
                        Map.of(
                                "tradingsymbol", "INFY",
                                "transaction_type", "BUY",
                                "quantity", 2,
                                "average_price", 1510.0,
                                "fill_timestamp", "2026-03-18 10:15:00+05:30"
                        )
                )
        )));

        Path importRoot = Files.createDirectories(tempDir.resolve("imports"));
        Path csvPath = importRoot.resolve("zerodha-tradebook.csv");
        Files.writeString(csvPath, """
                Trade Date,Trading Symbol,Trade Type,Quantity,Price,Exchange,Segment,Order ID,Trade ID
                16/03/2026,INFY,BUY,5,1500,NSE,EQ,OID1,TID1
                """);
        StockMetadataService stockMetadataService =
                new StockMetadataService(new DefaultResourceLoader(), objectMapper, "classpath:stock-universe.csv");
        ZerodhaTradebookService tradebookService = new ZerodhaTradebookService(
                new ZerodhaTradebookProperties(
                        importRoot.toString(),
                        tempDir.resolve("data").resolve("zerodha-tradebook.json").toString()
                ),
                stockMetadataService,
                objectMapper,
                Clock.fixed(Instant.parse("2026-03-18T04:00:00Z"), ZoneId.of("Asia/Kolkata"))
        );
        tradebookService.importTradebook(csvPath.toString());

        ZerodhaGatewayService service = new ZerodhaGatewayService(
                apiClient,
                emptyCache(),
                "free",
                new CountingLimiter(),
                new CountingLimiter(),
                new CountingLimiter(),
                new CountingLimiter(),
                tradebookService,
                Clock.fixed(Instant.parse("2026-03-18T04:00:00Z"), ZoneId.of("Asia/Kolkata"))
        );

        List<TradeSnapshot> trades = service.getTrades(LocalDate.of(2026, 3, 16), LocalDate.of(2026, 3, 18));

        assertEquals(2, trades.size());
        assertEquals(LocalDate.of(2026, 3, 16), trades.getFirst().tradeDate());
        assertEquals(LocalDate.of(2026, 3, 18), trades.getLast().tradeDate());
    }

    @Test
    void getTradesShouldUseImportedHistoryOutsideTodayWithoutThrowing(@TempDir Path tempDir) throws Exception {
        Path importRoot = Files.createDirectories(tempDir.resolve("imports"));
        Path csvPath = importRoot.resolve("zerodha-tradebook.csv");
        Files.writeString(csvPath, """
                Trade Date,Trading Symbol,Trade Type,Quantity,Price,Exchange,Segment,Order ID,Trade ID
                16/03/2026,INFY,BUY,5,1500,NSE,EQ,OID1,TID1
                """);
        StockMetadataService stockMetadataService =
                new StockMetadataService(new DefaultResourceLoader(), objectMapper, "classpath:stock-universe.csv");
        ZerodhaTradebookService tradebookService = new ZerodhaTradebookService(
                new ZerodhaTradebookProperties(
                        importRoot.toString(),
                        tempDir.resolve("data").resolve("zerodha-tradebook.json").toString()
                ),
                stockMetadataService,
                objectMapper,
                Clock.fixed(Instant.parse("2026-03-18T04:00:00Z"), ZoneId.of("Asia/Kolkata"))
        );
        tradebookService.importTradebook(csvPath.toString());

        ZerodhaGatewayService service = new ZerodhaGatewayService(
                new RecordingZerodhaApiClient(objectMapper),
                emptyCache(),
                "free",
                new CountingLimiter(),
                new CountingLimiter(),
                new CountingLimiter(),
                new CountingLimiter(),
                tradebookService,
                Clock.fixed(Instant.parse("2026-03-18T04:00:00Z"), ZoneId.of("Asia/Kolkata"))
        );

        List<TradeSnapshot> trades = service.getTrades(LocalDate.of(2026, 3, 16), LocalDate.of(2026, 3, 17));

        assertEquals(1, trades.size());
        assertEquals(LocalDate.of(2026, 3, 16), trades.getFirst().tradeDate());
    }

    @Test
    void zerodhaDateParsing_shouldAcceptOffsetsWithoutColon() {
        RecordingZerodhaApiClient apiClient = new RecordingZerodhaApiClient(objectMapper);
        ZerodhaInstrumentCache cache = seededCache();
        apiClient.getResponses.put("/quote", json(Map.of(
                "status", "success",
                "data", Map.of(
                        "NSE:INFY", Map.of(
                                "last_price", 1600.0,
                                "volume", 10000,
                                "timestamp", "2026-03-16T17:33:44+0530",
                                "ohlc", Map.of("open", 1590.0, "high", 1610.0, "low", 1585.0, "close", 1588.0),
                                "depth", Map.of(
                                        "buy", List.of(Map.of("price", 1599.0)),
                                        "sell", List.of(Map.of("price", 1601.0))
                                )
                        )
                )
        )));
        apiClient.getResponses.put("/instruments/historical/1594/day", json(Map.of(
                "status", "success",
                "data", Map.of(
                        "candles", List.of(
                                List.of("2025-09-16T00:00:00+0530", 1580.0, 1600.0, 1575.0, 1590.0, 9000.0, 0.0)
                        )
                )
        )));

        ZerodhaGatewayService service = new ZerodhaGatewayService(apiClient, cache, "paid",
                new CountingLimiter(), new CountingLimiter(), new CountingLimiter(), new CountingLimiter());

        var quote = service.getQuote("INFY", "NSE", "cash");
        var candles = service.getHistoricalCharts("INFY", "NSE", "cash", "day",
                LocalDate.of(2025, 9, 16), LocalDate.of(2025, 9, 16));

        assertEquals(1600.0, quote.ltp());
        assertEquals(1, candles.size());
        assertEquals(1590.0, candles.getFirst().close());
    }

    @Test
    void paidTierOptionChainShouldUseInstrumentCacheAndSingleBatchQuoteCall() {
        RecordingZerodhaApiClient apiClient = new RecordingZerodhaApiClient(objectMapper);
        ZerodhaInstrumentCache cache = seededCache();
        apiClient.getResponses.put("/quote", json(Map.of(
                "status", "success",
                "data", Map.of(
                        "NFO:NIFTY24MAR22500CE", Map.of("oi", 1200.0),
                        "NFO:NIFTY24MAR22600CE", Map.of("oi", 900.0)
                )
        )));

        CountingLimiter historical = new CountingLimiter();
        ZerodhaGatewayService service = new ZerodhaGatewayService(apiClient, cache, "paid",
                new CountingLimiter(), historical, new CountingLimiter(), new CountingLimiter());

        var chain = service.getOptionChain("NIFTY", "2026-03-19", "call");

        assertEquals(1, apiClient.quoteCalls);
        assertEquals(List.of("NFO:NIFTY24MAR22500CE", "NFO:NIFTY24MAR22600CE"), apiClient.lastQueryParams.get("i"));
        assertEquals(2, chain.size());
        assertEquals(22500.0, chain.getFirst().strikePrice());
        assertEquals(1200.0, chain.getFirst().openInterest());
        assertEquals(1, historical.calls);
    }

    private JsonNode json(Map<String, Object> payload) {
        return objectMapper.valueToTree(payload);
    }

    private ZerodhaInstrumentCache emptyCache() {
        return new ZerodhaInstrumentCache(
                Path.of(System.getProperty("java.io.tmpdir"), "zerodha-cache-empty-" + System.nanoTime()),
                Clock.system(ZoneId.of("Asia/Kolkata")),
                exchange -> "instrument_token,tradingsymbol,exchange,name,expiry,strike,instrument_type\n");
    }

    private ZerodhaInstrumentCache seededCache() {
        ZerodhaInstrumentCache cache = new ZerodhaInstrumentCache(
                Path.of(System.getProperty("java.io.tmpdir"), "zerodha-cache-seeded-" + System.nanoTime()),
                Clock.system(ZoneId.of("Asia/Kolkata")),
                exchange -> switch (exchange) {
                    case "NSE" -> """
                            instrument_token,tradingsymbol,exchange,name,expiry,strike,instrument_type
                            1594,INFY,NSE,INFY,,,EQ
                            256265,NIFTY 50,NSE,NIFTY,,,INDEX
                            """;
                    case "BSE" -> "instrument_token,tradingsymbol,exchange,name,expiry,strike,instrument_type\n";
                    case "NFO" -> """
                            instrument_token,tradingsymbol,exchange,name,expiry,strike,instrument_type
                            50101,NIFTY24MAR22500CE,NFO,NIFTY,2026-03-19,22500,CE
                            50102,NIFTY24MAR22600CE,NFO,NIFTY,2026-03-19,22600,CE
                            50103,NIFTY24MAR22500PE,NFO,NIFTY,2026-03-19,22500,PE
                            """;
                    default -> throw new IllegalArgumentException(exchange);
                });
        cache.initialize();
        return cache;
    }

    private static final class CountingLimiter implements RateLimiter {
        private int calls;

        @Override
        public void acquire() {
            calls++;
        }

        private int calls() {
            return calls;
        }
    }

    private static final class RecordingZerodhaApiClient extends ZerodhaApiClient {

        private final Map<String, JsonNode> getResponses = new HashMap<>();
        private final Map<String, JsonNode> postFormResponses = new HashMap<>();
        private final Map<String, JsonNode> postJsonResponses = new HashMap<>();
        private Map<String, String> lastFormBody = Map.of();
        private List<Map<String, Object>> lastChargesRequest = List.of();
        private Map<String, List<String>> lastQueryParams = Map.of();
        private int quoteCalls;

        private RecordingZerodhaApiClient(ObjectMapper objectMapper) {
            super(null, objectMapper, new ZerodhaSessionManager(), "https://api.kite.trade");
        }

        @Override
        public JsonNode get(String path) {
            if ("/quote".equals(path)) {
                quoteCalls++;
            }
            return getResponses.get(path);
        }

        @Override
        @SuppressWarnings("unchecked")
        public JsonNode get(String path, Map<String, ?> params) {
            lastQueryParams = new HashMap<>();
            for (Map.Entry<String, ?> entry : params.entrySet()) {
                if (entry.getValue() instanceof List<?> values) {
                    lastQueryParams.put(entry.getKey(), (List<String>) values);
                } else {
                    lastQueryParams.put(entry.getKey(), List.of(String.valueOf(entry.getValue())));
                }
            }
            if ("/quote".equals(path)) {
                quoteCalls++;
            }
            return getResponses.get(path);
        }

        @Override
        public JsonNode postForm(String path, Map<String, String> body) {
            lastFormBody = body;
            return postFormResponses.get(path);
        }

        @Override
        @SuppressWarnings("unchecked")
        public JsonNode postJson(String path, Object body) {
            lastChargesRequest = (List<Map<String, Object>>) body;
            return postJsonResponses.get(path);
        }
    }
}
