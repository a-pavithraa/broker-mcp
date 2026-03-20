package com.broker.service;

import com.broker.exception.BreezeApiException;
import com.broker.model.AnalysisModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
@ConditionalOnProperty(name = "breeze.enabled", havingValue = "true")
public class BreezeGatewayService implements BrokerDataProvider {

    private static final Logger log = LoggerFactory.getLogger(BreezeGatewayService.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final int DAILY_CALL_WARNING_THRESHOLD = 4_000;

    private final BreezeApiClient breezeApiClient;
    private final StockMetadataService stockMetadataService;
    private final List<String> tradeExchanges;
    private final Map<String, CacheEntry<?>> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> cacheGuards = new ConcurrentHashMap<>();
    private final TokenBucketRateLimiter rateLimiter;
    private final AtomicInteger dailyCallCount = new AtomicInteger();
    private final AtomicLong callCountDayKey = new AtomicLong(currentDayKey());

    public BreezeGatewayService(
            BreezeApiClient breezeApiClient,
            StockMetadataService stockMetadataService,
            @Value("${broker.trade-exchanges:NSE,BSE}") List<String> tradeExchanges) {
        this.breezeApiClient = breezeApiClient;
        this.stockMetadataService = stockMetadataService;
        this.tradeExchanges = tradeExchanges;
        this.rateLimiter = new TokenBucketRateLimiter(
                100d / 60d,
                10,
                "Interrupted while waiting for Breeze API rate limit"
        );
    }

    @Override
    public List<HoldingSnapshot> getPortfolioHoldings() {
        return cached("portfolio-holdings", marketAwareTtl(Duration.ofMinutes(5), Duration.ofHours(4)), () -> {
            Map<String, String> params = new HashMap<>();
            params.put("exchange_code", "NSE");
            JsonNode response = apiGet("/portfolioholdings", params);
            JsonNode success = requireSuccessArray(response, "/portfolioholdings");

            List<HoldingSnapshot> holdings = new ArrayList<>();
            for (JsonNode item : success) {
                String iciciCode = GatewayJsonHelper.text(item, "stock_code");
                String canonicalCode = stockMetadataService.resolveIciciToNse(iciciCode);
                String isin = GatewayJsonHelper.firstNonBlank(GatewayJsonHelper.text(item, "stock_ISIN"), metadataIsin(iciciCode));
                holdings.add(new HoldingSnapshot(
                        canonicalCode,
                        GatewayJsonHelper.text(item, "stock_name", GatewayJsonHelper.text(item, "company_name")),
                        GatewayJsonHelper.text(item, "exchange_code", "NSE"),
                        GatewayJsonHelper.number(item, "quantity"),
                        GatewayJsonHelper.number(item, "average_price"),
                        GatewayJsonHelper.number(item, "current_market_price"),
                        GatewayJsonHelper.number(item, "booked_profit_loss"),
                        GatewayJsonHelper.number(item, "change_percentage"),
                        "icici",
                        isin
                ));
            }
            return holdings;
        });
    }

    @Override
    public FundsSnapshot getFunds() {
        return cached("funds", marketAwareTtl(Duration.ofMinutes(5), Duration.ofHours(4)), () -> {
            JsonNode response = apiGet("/funds");
            JsonNode success = requireSuccessObject(response, "/funds");
            return new FundsSnapshot(
                    GatewayJsonHelper.number(success, "total_bank_balance", "total_balance"),
                    GatewayJsonHelper.number(success, "unallocated_balance"),
                    GatewayJsonHelper.text(success, "bank_account"),
                    "icici",
                    fundDetails(success)
            );
        });
    }

    @Override
    public QuoteSnapshot getQuote(String stockCode, String exchangeCode, String productType) {
        String key = "quote:" + stockCode + ":" + exchangeCode + ":" + productType;
        return cached(key, marketAwareTtl(Duration.ofSeconds(60), Duration.ofHours(12)), () -> {
            String iciciCode = stockMetadataService.resolveNseToIcici(stockCode);
            Map<String, String> params = new HashMap<>();
            params.put("stock_code", iciciCode);
            params.put("exchange_code", exchangeCode);
            params.put("product_type", productType);
            JsonNode response = apiGet("/quotes", params);
            JsonNode success = requireSuccessArray(response, "/quotes");
            if (success.isEmpty()) {
                throw new BreezeApiException("No quote data found for " + stockCode);
            }
            JsonNode item = null;
            for (JsonNode node : success) {
                if (exchangeCode.equalsIgnoreCase(GatewayJsonHelper.text(node, "exchange_code"))) {
                    item = node;
                    break;
                }
            }
            if (item == null) {
                item = success.get(0);
            }
            return new QuoteSnapshot(
                    stockCode,
                    GatewayJsonHelper.text(item, "exchange_code", exchangeCode),
                    GatewayJsonHelper.number(item, "ltp"),
                    GatewayJsonHelper.number(item, "previous_close"),
                    GatewayJsonHelper.number(item, "open"),
                    GatewayJsonHelper.number(item, "high"),
                    GatewayJsonHelper.number(item, "low"),
                    GatewayJsonHelper.number(item, "total_quantity_traded"),
                    GatewayJsonHelper.number(item, "best_bid_price"),
                    GatewayJsonHelper.number(item, "best_offer_price"),
                    parseDateTime(GatewayJsonHelper.text(item, "ltt"))
            );
        });
    }

    @Override
    public Map<String, QuoteSnapshot> getQuotes(List<String> stockCodes, String exchangeCode, String productType) {
        Map<String, QuoteSnapshot> result = new LinkedHashMap<>();
        for (String code : stockCodes) {
            try {
                result.put(code, getQuote(code, exchangeCode, productType));
            } catch (Exception e) {
                log.warn("Failed to fetch Breeze quote for {}: {}", code, e.getMessage());
            }
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
        String key = "history:%s:%s:%s:%s:%s".formatted(stockCode, exchangeCode, interval, fromDate, toDate);
        Duration ttl = "day".equals(interval)
                ? marketAwareTtl(Duration.ofHours(6), Duration.ofHours(24))
                : marketAwareTtl(Duration.ofMinutes(5), Duration.ofHours(12));
        return cached(key, ttl, () -> {
            String iciciCode = stockMetadataService.resolveNseToIcici(stockCode);
            Map<String, String> params = new HashMap<>();
            params.put("stock_code", iciciCode);
            params.put("exchange_code", exchangeCode);
            params.put("product_type", productType);
            params.put("interval", interval);
            params.put("from_date", toIsoDate(fromDate));
            params.put("to_date", toIsoDate(toDate));
            JsonNode response = apiGet("/historicalcharts", params);
            JsonNode success = requireSuccessArray(response, "/historicalcharts");

            List<HistoricalCandle> candles = new ArrayList<>();
            for (JsonNode item : success) {
                candles.add(new HistoricalCandle(
                        parseDateTime(GatewayJsonHelper.text(item, "datetime")),
                        GatewayJsonHelper.number(item, "open"),
                        GatewayJsonHelper.number(item, "high"),
                        GatewayJsonHelper.number(item, "low"),
                        GatewayJsonHelper.number(item, "close"),
                        GatewayJsonHelper.number(item, "volume"),
                        GatewayJsonHelper.number(item, "open_interest")
                ));
            }
            candles.sort(Comparator.comparing(HistoricalCandle::dateTime));
            return candles;
        });
    }

    @Override
    public List<TradeSnapshot> getTrades(LocalDate fromDate, LocalDate toDate) {
        String key = "trades:%s:%s".formatted(fromDate, toDate);
        return cached(key, marketAwareTtl(Duration.ofMinutes(30), Duration.ofHours(4)), () -> {
            List<TradeSnapshot> trades = new ArrayList<>();
            for (String exchange : tradeExchanges) {
                try {
                    Map<String, String> params = new HashMap<>();
                    params.put("from_date", toIsoDate(fromDate));
                    params.put("to_date", toIsoDate(toDate));
                    params.put("exchange_code", exchange);
                    params.put("product_type", "");
                    params.put("action", "");
                    params.put("stock_code", "");
                    JsonNode response = apiGet("/trades", params);
                    JsonNode success = requireSuccessArray(response, "/trades");
                    for (JsonNode item : success) {
                        String iciciCode = GatewayJsonHelper.text(item, "stock_code");
                        double qty = GatewayJsonHelper.number(item, "quantity");
                        double totalCost = GatewayJsonHelper.number(item, "average_cost");
                        double perUnitPrice = qty > 0 ? totalCost / qty : 0;
                        trades.add(new TradeSnapshot(
                                stockMetadataService.resolveIciciToNse(iciciCode),
                                GatewayJsonHelper.text(item, "action").toLowerCase(),
                                qty,
                                perUnitPrice,
                                parseDate(GatewayJsonHelper.text(item, "trade_date")),
                                "icici"
                        ));
                    }
                } catch (BreezeApiException e) {
                    log.warn("Failed to fetch Breeze trades for {}: {}", exchange, e.getMessage());
                }
            }
            return trades;
        });
    }

    @Override
    public List<GttOrderSnapshot> getGttOrders() {
        return cached("gtt-orders", marketAwareTtl(Duration.ofMinutes(1), Duration.ofHours(4)), () -> {
            BreezeApiException lastError = null;

                try {
                    JsonNode success = requireSuccessArray(apiGet("/gttorder", gttOrderParams()), "/gttorder");
                    List<GttOrderSnapshot> orders = new ArrayList<>();
                    for (JsonNode item : success) {
                        orders.add(new GttOrderSnapshot(
                                GatewayJsonHelper.text(item, "fresh_order_id"),
                                stockMetadataService.resolveIciciToNse(GatewayJsonHelper.text(item, "stock_code")),
                                GatewayJsonHelper.number(item, "quantity"),
                                "icici"
                        ));
                    }
                    return orders;
                } catch (BreezeApiException ex) {
                    lastError = ex;
                }

            throw lastError == null ? new BreezeApiException("Unable to fetch GTT orders") : lastError;
        });
    }

    private Map<String, String> gttOrderParams() {
        Map<String, String> params = new HashMap<>();
        params.put("exchange_code", "NFO");
        params.put("from_date", toIsoDate(LocalDate.now(INDIA).minusMonths(6)));
        params.put("to_date", toIsoDate(LocalDate.now(INDIA)));
        return params;
    }

    @Override
    public List<OptionChainSnapshot> getOptionChain(String stockCode, String expiryDate, String right) {
        String key = "option-chain:%s:%s:%s".formatted(stockCode, expiryDate, right);
        return cached(key, marketAwareTtl(Duration.ofSeconds(60), Duration.ofHours(12)), () -> {
            Map<String, String> params = new HashMap<>();
            params.put("stock_code", stockMetadataService.resolveNseToIcici(stockCode));
            params.put("exchange_code", "NFO");
            params.put("product_type", "options");
            params.put("expiry_date", expiryDate);
            params.put("right", right);
            JsonNode response = apiGet("/OptionChain", params);
            JsonNode success = requireSuccessArray(response, "/OptionChain");

            List<OptionChainSnapshot> chain = new ArrayList<>();
            for (JsonNode item : success) {
                chain.add(new OptionChainSnapshot(
                        GatewayJsonHelper.number(item, "strike_price"),
                        GatewayJsonHelper.number(item, "open_interest")
                ));
            }
            return chain;
        });
    }

    @Override
    public JsonNode previewOrder(Map<String, String> body) {
        return apiGet("/preview_order", toIciciOrderBody(body, false));
    }

    @Override
    public JsonNode placeOrder(Map<String, String> body) {
        return apiPost("/order", toIciciOrderBody(body, false));
    }

    @Override
    public JsonNode getOrderDetail(String exchangeCode, String orderId) {
        return apiGet("/order", Map.of("exchange_code", exchangeCode, "order_id", orderId));
    }

    @Override
    public JsonNode placeGttOrder(Map<String, String> body) {
        return apiPost("/gttorder", toIciciOrderBody(body, true));
    }

    private JsonNode apiGet(String endpoint) {
        return apiGet(endpoint, Map.of());
    }

    private JsonNode apiGet(String endpoint, Map<String, String> params) {
        acquirePermit(endpoint);
        return breezeApiClient.get(endpoint, params);
    }

    private JsonNode apiPost(String endpoint, Map<String, String> body) {
        acquirePermit(endpoint);
        return breezeApiClient.post(endpoint, body);
    }

    private void acquirePermit(String endpoint) {
        rateLimiter.acquire();
        int current = incrementDailyCallCount(endpoint);
        if (current == DAILY_CALL_WARNING_THRESHOLD) {
            log.warn("Breeze API call volume has reached 80% of the 5000/day cap");
        }
    }

    private int incrementDailyCallCount(String endpoint) {
        long todayKey = currentDayKey();
        long previous = callCountDayKey.get();
        if (previous != todayKey && callCountDayKey.compareAndSet(previous, todayKey)) {
            dailyCallCount.set(0);
        }
        int count = dailyCallCount.incrementAndGet();
        if (count > 5_000) {
            log.warn("Breeze API call count exceeded 5000 for {}", endpoint);
        }
        return count;
    }

    private Map<String, String> fundDetails(JsonNode success) {
        Map<String, String> details = new LinkedHashMap<>();
        copyIfPresent(details, "allocated_equity", GatewayJsonHelper.text(success, "allocated_equity"));
        copyIfPresent(details, "allocated_fno", GatewayJsonHelper.text(success, "allocated_fno"));
        copyIfPresent(details, "allocated_commodity", GatewayJsonHelper.text(success, "allocated_commodity"));
        copyIfPresent(details, "allocated_currency", GatewayJsonHelper.text(success, "allocated_currency"));
        copyIfPresent(details, "block_by_trade_equity", GatewayJsonHelper.text(success, "block_by_trade_equity"));
        copyIfPresent(details, "block_by_trade_fno", GatewayJsonHelper.text(success, "block_by_trade_fno"));
        copyIfPresent(details, "block_by_trade_commodity", GatewayJsonHelper.text(success, "block_by_trade_commodity"));
        copyIfPresent(details, "block_by_trade_currency", GatewayJsonHelper.text(success, "block_by_trade_currency"));
        return details;
    }

    private Map<String, String> toIciciOrderBody(Map<String, String> body, boolean gttOrder) {
        Map<String, String> translated = new HashMap<>();
        String symbol = GatewayJsonHelper.firstNonBlank(body.get("symbol"), body.get("stock_code"));
        String exchange = GatewayJsonHelper.firstNonBlank(body.get("exchange"), body.get("exchange_code"));
        String product = GatewayJsonHelper.firstNonBlank(body.get("product"), body.get("product_type"), "cash");

        copyIfPresent(translated, "stock_code", stockMetadataService.resolveNseToIcici(symbol));
        copyIfPresent(translated, "exchange_code", exchange);
        copyIfPresent(translated, gttOrder ? "product_type" : "product", product);
        copyIfPresent(translated, "action", body.get("action"));
        copyIfPresent(translated, "order_type", body.get("order_type"));
        copyIfPresent(translated, "quantity", body.get("quantity"));
        copyIfPresent(translated, "price", body.get("price"));
        copyIfPresent(translated, "trigger_price", body.get("trigger_price"));
        copyIfPresent(translated, "limit_price", body.get("limit_price"));
        copyIfPresent(translated, "validity", body.get("validity"));
        copyIfPresent(translated, "specialflag", body.get("specialflag"));
        return translated;
    }

    private String metadataIsin(String iciciCode) {
        var metadata = stockMetadataService.getMetadata(iciciCode);
        return metadata == null ? null : metadata.isin();
    }

    private static long currentDayKey() {
        LocalDate today = LocalDate.now(INDIA);
        return today.toEpochDay();
    }

    private JsonNode requireSuccessArray(JsonNode response, String endpoint) {
        if (response.has("Error") && !response.get("Error").isNull()) {
            throw new BreezeApiException(response.get("Error").asText());
        }
        JsonNode success = response.get("Success");
        if (success == null || !success.isArray()) {
            throw new BreezeApiException("Unexpected response from " + endpoint);
        }
        return success;
    }

    private JsonNode requireSuccessObject(JsonNode response, String endpoint) {
        if (response.has("Error") && !response.get("Error").isNull()) {
            throw new BreezeApiException(response.get("Error").asText());
        }
        JsonNode success = response.get("Success");
        if (success == null || !success.isObject()) {
            throw new BreezeApiException("Unexpected response from " + endpoint);
        }
        return success;
    }

    private static Duration marketAwareTtl(Duration duringMarket, Duration outsideMarket) {
        ZonedDateTime now = ZonedDateTime.now(INDIA);
        DayOfWeek day = now.getDayOfWeek();
        boolean weekday = day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
        LocalTime time = now.toLocalTime();
        boolean marketOpen = weekday && !time.isBefore(MARKET_OPEN) && time.isBefore(MARKET_CLOSE);
        return marketOpen ? duringMarket : outsideMarket;
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Duration ttl, java.util.function.Supplier<T> loader) {
        CacheEntry<T> cached = (CacheEntry<T>) cache.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }
        Object guard = cacheGuards.computeIfAbsent(key, ignored -> new Object());
        synchronized (guard) {
            CacheEntry<T> refreshed = (CacheEntry<T>) cache.get(key);
            if (refreshed != null && !refreshed.isExpired()) {
                return refreshed.value();
            }
            T value = loader.get();
            cache.put(key, new CacheEntry<>(value, ttl));
            return value;
        }
    }

    private static void copyIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static ZonedDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(raw);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(raw.replace(" ", "T")).atZone(ZoneOffset.UTC);
            } catch (DateTimeParseException secondIgnored) {
                return null;
            }
        }
    }

    private static final java.time.format.DateTimeFormatter BREEZE_DATE_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy", java.util.Locale.ENGLISH);

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.substring(0, Math.min(10, raw.length())));
        } catch (DateTimeParseException e) {
            return LocalDate.parse(raw.trim(), BREEZE_DATE_FORMAT);
        }
    }

    private static String toIsoDate(LocalDate date) {
        return date.atStartOfDay().atZone(ZoneOffset.UTC).toInstant().toString();
    }

    private record CacheEntry<T>(T value, long expiresAtMillis) {
        private CacheEntry(T value, Duration ttl) {
            this(value, System.currentTimeMillis() + ttl.toMillis());
        }

        private boolean isExpired() {
            return System.currentTimeMillis() >= expiresAtMillis;
        }
    }

}
