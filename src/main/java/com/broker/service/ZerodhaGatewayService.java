package com.broker.service;

import com.broker.exception.BreezeApiException;
import com.broker.exception.BrokerCapabilityException;
import com.broker.model.AnalysisModels.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
@ConditionalOnProperty(name = "zerodha.enabled", havingValue = "true")
public class ZerodhaGatewayService implements BrokerDataProvider {

    private static final java.time.ZoneId INDIA = java.time.ZoneId.of("Asia/Kolkata");
    private static final java.time.format.DateTimeFormatter HISTORICAL_QUERY_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final java.time.format.DateTimeFormatter OFFSET_WITHOUT_COLON =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

    private final ZerodhaApiClient apiClient;
    private final ZerodhaInstrumentCache instrumentCache;
    private final String tier;
    private final RateLimiter quotesLimiter;
    private final RateLimiter historicalLimiter;
    private final RateLimiter ordersLimiter;
    private final RateLimiter generalLimiter;
    private final ZerodhaTradebookService zerodhaTradebookService;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    @Autowired
    public ZerodhaGatewayService(
            ZerodhaApiClient apiClient,
            ZerodhaInstrumentCache instrumentCache,
            ObjectMapper objectMapper,
            @Value("${zerodha.tier:free}") String tier,
            ObjectProvider<ZerodhaTradebookService> zerodhaTradebookServiceProvider) {
        // Zerodha/Kite Connect API rate limits:
        // https://kite.trade/docs/connect/v3/exceptions/#api-rate-limit
        // Quote 1 req/s, Historical candle 3 req/s, Order placement 10 req/s, all other endpoints 10 req/s.
        // The same docs also mention 400 orders/minute, 5000 orders/day, and 25 modifications/order.
        // This service smooths the documented per-second rates locally; higher-level order limits are still
        // enforced by Zerodha server-side.
        this(apiClient, instrumentCache, objectMapper, tier,
                new TokenBucketRateLimiter(1, 1, "Interrupted while waiting for Zerodha API rate limit"),
                new TokenBucketRateLimiter(3, 3, "Interrupted while waiting for Zerodha API rate limit"),
                new TokenBucketRateLimiter(10, 10, "Interrupted while waiting for Zerodha API rate limit"),
                new TokenBucketRateLimiter(10, 10, "Interrupted while waiting for Zerodha API rate limit"),
                zerodhaTradebookServiceProvider.getIfAvailable(),
                Clock.system(INDIA));
    }

    ZerodhaGatewayService(
            ZerodhaApiClient apiClient,
            ZerodhaInstrumentCache instrumentCache,
            String tier,
            RateLimiter quotesLimiter,
            RateLimiter historicalLimiter,
            RateLimiter ordersLimiter,
            RateLimiter generalLimiter) {
        this(apiClient, instrumentCache, new ObjectMapper(), tier, quotesLimiter, historicalLimiter, ordersLimiter, generalLimiter, null, Clock.system(INDIA));
    }

    ZerodhaGatewayService(
            ZerodhaApiClient apiClient,
            ZerodhaInstrumentCache instrumentCache,
            String tier,
            RateLimiter quotesLimiter,
            RateLimiter historicalLimiter,
            RateLimiter ordersLimiter,
            RateLimiter generalLimiter,
            @Nullable ZerodhaTradebookService zerodhaTradebookService,
            Clock clock) {
        this(apiClient, instrumentCache, new ObjectMapper(), tier, quotesLimiter, historicalLimiter, ordersLimiter, generalLimiter, zerodhaTradebookService, clock);
    }

    ZerodhaGatewayService(
            ZerodhaApiClient apiClient,
            ZerodhaInstrumentCache instrumentCache,
            ObjectMapper objectMapper,
            String tier,
            RateLimiter quotesLimiter,
            RateLimiter historicalLimiter,
            RateLimiter ordersLimiter,
            RateLimiter generalLimiter,
            @Nullable ZerodhaTradebookService zerodhaTradebookService,
            Clock clock) {
        this.apiClient = apiClient;
        this.instrumentCache = instrumentCache;
        this.objectMapper = objectMapper;
        this.tier = normalizeTier(tier);
        this.quotesLimiter = quotesLimiter;
        this.historicalLimiter = historicalLimiter;
        this.ordersLimiter = ordersLimiter;
        this.generalLimiter = generalLimiter;
        this.zerodhaTradebookService = zerodhaTradebookService;
        this.clock = clock.withZone(INDIA);
    }

    @Override
    public List<HoldingSnapshot> getPortfolioHoldings() {
        generalLimiter.acquire();
        JsonNode response = apiClient.get("/portfolio/holdings");
        ArrayNode data = requireArrayData(response, "/portfolio/holdings");
        List<HoldingSnapshot> holdings = new ArrayList<>();
        for (JsonNode item : data) {
            // quantity is settled shares; t1_quantity is shares bought but not yet settled (T+1).
            // Both are owned — use the sum as the effective holding quantity.
            double quantity = GatewayJsonHelper.number(item, "quantity") + GatewayJsonHelper.number(item, "t1_quantity");
            holdings.add(new HoldingSnapshot(
                    GatewayJsonHelper.text(item, "tradingsymbol"),
                    GatewayJsonHelper.text(item, "tradingsymbol"),
                    GatewayJsonHelper.text(item, "exchange", "NSE"),
                    quantity,
                    GatewayJsonHelper.number(item, "average_price"),
                    GatewayJsonHelper.number(item, "last_price"),
                    GatewayJsonHelper.number(item, "pnl"),
                    GatewayJsonHelper.number(item, "day_change_percentage"),
                    "zerodha",
                    GatewayJsonHelper.text(item, "isin")
            ));
        }
        return holdings;
    }

    @Override
    public FundsSnapshot getFunds() {
        generalLimiter.acquire();
        JsonNode response = apiClient.get("/user/margins");
        JsonNode data = requireObjectData(response, "/user/margins");
        JsonNode equity = data.path("equity");
        JsonNode commodity = data.path("commodity");

        double equityAvailable = nestedNumber(equity, "available", "live_balance");
        double commodityAvailable = nestedNumber(commodity, "available", "live_balance");
        double collateral = nestedNumber(equity, "available", "collateral")
                + nestedNumber(commodity, "available", "collateral");
        double spanUsed = nestedNumber(equity, "utilised", "span")
                + nestedNumber(commodity, "utilised", "span");
        double exposureUsed = nestedNumber(equity, "utilised", "exposure")
                + nestedNumber(commodity, "utilised", "exposure");
        double totalBalance = GatewayJsonHelper.number(equity, "net") + GatewayJsonHelper.number(commodity, "net");

        Map<String, String> details = new LinkedHashMap<>();
        details.put("equity_available", stringify(equityAvailable));
        details.put("commodity_available", stringify(commodityAvailable));
        details.put("collateral", stringify(collateral));
        details.put("span_used", stringify(spanUsed));
        details.put("exposure_used", stringify(exposureUsed));

        return new FundsSnapshot(
                totalBalance,
                equityAvailable + commodityAvailable,
                "",
                "zerodha",
                details
        );
    }

    @Override
    public QuoteSnapshot getQuote(String stockCode, String exchangeCode, String productType) {
        Map<String, QuoteSnapshot> quotes = getQuotes(List.of(stockCode), exchangeCode, productType);
        QuoteSnapshot quote = quotes.get(stockCode);
        if (quote == null) {
            throw new BreezeApiException("No Zerodha quote data found for " + stockCode);
        }
        return quote;
    }

    @Override
    public Map<String, QuoteSnapshot> getQuotes(List<String> stockCodes, String exchangeCode, String productType) {
        requirePaidCapability("live quotes");
        quotesLimiter.acquire();
        Map<String, String> instrumentByCode = new LinkedHashMap<>();
        for (String stockCode : stockCodes) {
            instrumentByCode.put(stockCode, instrumentCache.resolveQuoteInstrument(exchangeCode, stockCode));
        }
        JsonNode response = apiClient.get("/quote", Map.of("i", List.copyOf(instrumentByCode.values())));
        JsonNode data = requireObjectData(response, "/quote");
        Map<String, QuoteSnapshot> quotes = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : instrumentByCode.entrySet()) {
            JsonNode item = data.get(entry.getValue());
            if (item == null || item.isNull()) {
                continue;
            }
            quotes.put(entry.getKey(), toQuoteSnapshot(entry.getKey(), exchangeCode, item));
        }
        return quotes;
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
        requirePaidCapability("historical candle data");
        historicalLimiter.acquire();
        long instrumentToken = instrumentCache.getInstrumentToken(exchangeCode, stockCode);
        JsonNode response = apiClient.get(
                "/instruments/historical/" + instrumentToken + "/" + interval,
                Map.of(
                        "from", historicalQueryDateTime(fromDate),
                        "to", historicalQueryDateTime(toDate.plusDays(1)),
                        "oi", 1
                ));
        ArrayNode candles = requireArrayData(response.path("data").path("candles"), "/instruments/historical");
        List<HistoricalCandle> result = new ArrayList<>();
        for (JsonNode candle : candles) {
            result.add(new HistoricalCandle(
                    parseDateTime(candle.get(0).asText()),
                    candle.get(1).asDouble(),
                    candle.get(2).asDouble(),
                    candle.get(3).asDouble(),
                    candle.get(4).asDouble(),
                    candle.get(5).asDouble(),
                    candle.size() > 6 ? candle.get(6).asDouble() : 0
            ));
        }
        result.sort(Comparator.comparing(HistoricalCandle::dateTime));
        return result;
    }

    @Override
    public List<TradeSnapshot> getTrades(LocalDate fromDate, LocalDate toDate) {
        LocalDate today = LocalDate.now(clock);
        List<TradeSnapshot> importedTrades = zerodhaTradebookService == null
                ? List.of()
                : zerodhaTradebookService.getImportedTrades(fromDate, toDate);
        boolean requestedRangeIncludesToday = !today.isBefore(fromDate) && !today.isAfter(toDate);
        if (!requestedRangeIncludesToday && importedTrades.isEmpty()) {
            throw new BrokerCapabilityException(
                    "Zerodha trade history is limited to today's trades via the Kite API. " +
                    "Historical trade data is not available programmatically.");
        }
        List<TradeSnapshot> trades = new ArrayList<>(importedTrades);

        boolean includeSameDayApiTrades = requestedRangeIncludesToday && shouldMergeTodayApiTrades(today);
        if (includeSameDayApiTrades) {
            generalLimiter.acquire();
            JsonNode response = apiClient.get("/trades");
            ArrayNode data = requireArrayData(response, "/trades");
            for (JsonNode item : data) {
                LocalDate tradeDate = parseTradeDate(item);
                if (tradeDate == null || tradeDate.isBefore(fromDate) || tradeDate.isAfter(toDate)) {
                    continue;
                }
                trades.add(new TradeSnapshot(
                        GatewayJsonHelper.text(item, "tradingsymbol"),
                        GatewayJsonHelper.text(item, "transaction_type").toLowerCase(Locale.ROOT),
                        GatewayJsonHelper.number(item, "quantity"),
                        GatewayJsonHelper.number(item, "average_price"),
                        tradeDate,
                        "zerodha"
                ));
            }
        }
        trades.sort(Comparator.comparing(TradeSnapshot::tradeDate, Comparator.nullsLast(Comparator.naturalOrder())));
        return trades;
    }

    @Override
    public List<GttOrderSnapshot> getGttOrders() {
        generalLimiter.acquire();
        JsonNode response = apiClient.get("/gtt/triggers");
        ArrayNode data = requireArrayData(response, "/gtt/triggers");
        List<GttOrderSnapshot> orders = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode firstOrder = item.path("orders").isArray() && !item.path("orders").isEmpty()
                    ? item.path("orders").get(0)
                    : null;
            String tradingsymbol = GatewayJsonHelper.text(item.path("condition"), "tradingsymbol");
            double quantity = firstOrder == null ? 0 : GatewayJsonHelper.number(firstOrder, "quantity");
            orders.add(new GttOrderSnapshot(
                    GatewayJsonHelper.text(item, "id"),
                    tradingsymbol,
                    quantity,
                    "zerodha"
            ));
        }
        return orders;
    }

    @Override
    public List<OptionChainSnapshot> getOptionChain(String stockCode, String expiryDate, String right) {
        requirePaidCapability("option chain data");
        historicalLimiter.acquire();
        LocalDate expiry = LocalDate.parse(expiryDate);
        List<ZerodhaInstrumentCache.DerivativeInstrument> contracts = instrumentCache.findOptionContracts(stockCode, expiry, right);
        if (contracts.isEmpty()) {
            return List.of();
        }
        List<String> instruments = contracts.stream()
                .map(ZerodhaInstrumentCache.DerivativeInstrument::quoteInstrument)
                .toList();
        JsonNode response = apiClient.get("/quote", Map.of("i", instruments));
        JsonNode data = requireObjectData(response, "/quote");
        List<OptionChainSnapshot> snapshots = new ArrayList<>();
        for (ZerodhaInstrumentCache.DerivativeInstrument contract : contracts) {
            JsonNode item = data.get(contract.quoteInstrument());
            if (item == null || item.isNull()) {
                continue;
            }
            double openInterest = GatewayJsonHelper.number(item, "oi");
            snapshots.add(new OptionChainSnapshot(contract.strikePrice(), openInterest));
        }
        snapshots.sort(Comparator.comparingDouble(OptionChainSnapshot::strikePrice));
        return snapshots;
    }

    @Override
    public JsonNode previewOrder(Map<String, String> body) {
        ordersLimiter.acquire();
        JsonNode response = apiClient.postJson("/charges/orders", List.of(toChargesRequest(body)));
        ArrayNode data = requireArrayData(response, "/charges/orders");
        JsonNode first = data.isEmpty() ? null : data.get(0);
        if (first == null) {
            return errorResponse("No charge preview returned by Zerodha");
        }
        JsonNode charges = first.path("charges");
        return successResponse(Map.of(
                "brokerage", GatewayJsonHelper.number(charges, "brokerage"),
                "stt", GatewayJsonHelper.number(charges, "transaction_tax"),
                "exchange_turnover_charges", GatewayJsonHelper.number(charges, "exchange_turnover_charge"),
                "stamp_duty", GatewayJsonHelper.number(charges, "stamp_duty"),
                "sebi_charges", GatewayJsonHelper.number(charges, "sebi_turnover_charge"),
                "gst", nestedNumber(charges, "gst", "total")
        ));
    }

    @Override
    public JsonNode placeOrder(Map<String, String> body) {
        ordersLimiter.acquire();
        if ("sell".equalsIgnoreCase(body.get("action")) && "cash".equalsIgnoreCase(body.getOrDefault("product", "cash"))) {
            return errorResponse("Sell-side CNC orders on Zerodha require prior CDSL TPIN/DDPI authorisation in Kite.");
        }
        JsonNode response = apiClient.postForm("/orders/regular", toRegularOrderRequest(body));
        String orderId = response.path("data").path("order_id").asText("");
        return successResponse(Map.of(
                "order_id", orderId,
                "message", response.path("message").asText("Order submitted")
        ));
    }

    @Override
    public JsonNode getOrderDetail(String exchangeCode, String orderId) {
        generalLimiter.acquire();
        JsonNode response = apiClient.get("/orders/" + orderId);
        ArrayNode data = requireArrayData(response, "/orders/{order_id}");
        JsonNode latest = data.isEmpty() ? null : data.get(data.size() - 1);
        if (latest == null) {
            return errorResponse("No Zerodha order detail returned for " + orderId);
        }
        return successResponse(Map.of(
                "status", GatewayJsonHelper.text(latest, "status"),
                "message", GatewayJsonHelper.text(latest, "status_message", GatewayJsonHelper.text(latest, "status"))
        ));
    }

    @Override
    public JsonNode placeGttOrder(Map<String, String> body) {
        ordersLimiter.acquire();
        return errorResponse("Zerodha GTT placement is not supported in this phase.");
    }

    private Map<String, String> toRegularOrderRequest(Map<String, String> body) {
        Map<String, String> request = new HashMap<>();
        request.put("tradingsymbol", required(body, "symbol"));
        request.put("exchange", body.getOrDefault("exchange", "NSE"));
        request.put("transaction_type", body.getOrDefault("action", "").toUpperCase(Locale.ROOT));
        request.put("order_type", body.getOrDefault("order_type", "market").toUpperCase(Locale.ROOT));
        request.put("quantity", required(body, "quantity"));
        request.put("product", canonicalProductToZerodha(body.getOrDefault("product", "cash")));
        request.put("validity", body.getOrDefault("validity", "DAY").toUpperCase(Locale.ROOT));
        String price = body.getOrDefault("price", "0");
        request.put("price", "market".equalsIgnoreCase(body.get("order_type")) ? "0" : price);
        return request;
    }

    private Map<String, Object> toChargesRequest(Map<String, String> body) {
        Map<String, Object> request = new HashMap<>();
        request.put("exchange", body.getOrDefault("exchange", "NSE"));
        request.put("tradingsymbol", required(body, "symbol"));
        request.put("transaction_type", body.getOrDefault("action", "").toUpperCase(Locale.ROOT));
        request.put("variety", "regular");
        request.put("product", canonicalProductToZerodha(body.getOrDefault("product", "cash")));
        request.put("order_type", body.getOrDefault("order_type", "market").toUpperCase(Locale.ROOT));
        request.put("quantity", Integer.parseInt(required(body, "quantity")));
        request.put("price", Double.parseDouble(body.getOrDefault("price", "0")));
        return request;
    }

    private String canonicalProductToZerodha(String product) {
        return switch (product == null ? "" : product.trim().toLowerCase(Locale.ROOT)) {
            case "cash" -> "CNC";
            case "margin" -> "MIS";
            default -> product == null ? "CNC" : product.toUpperCase(Locale.ROOT);
        };
    }

    private void requirePaidCapability(String capability) {
        if (!"paid".equals(tier)) {
            throw new BrokerCapabilityException("Zerodha " + capability + " requires zerodha.tier=paid");
        }
    }

    private static String normalizeTier(String tier) {
        return tier == null ? "free" : tier.trim().toLowerCase(Locale.ROOT);
    }

    private ArrayNode requireArrayData(JsonNode response, String endpoint) {
        JsonNode data = response != null && response.has("data") ? response.path("data") : response;
        if (!data.isArray()) {
            throw new BreezeApiException("Unexpected Zerodha response from " + endpoint);
        }
        return (ArrayNode) data;
    }

    private JsonNode requireObjectData(JsonNode response, String endpoint) {
        JsonNode data = response.path("data");
        if (!data.isObject()) {
            throw new BreezeApiException("Unexpected Zerodha response from " + endpoint);
        }
        return data;
    }

    private QuoteSnapshot toQuoteSnapshot(String stockCode, String exchangeCode, JsonNode item) {
        JsonNode ohlc = item.path("ohlc");
        JsonNode buy = item.path("depth").path("buy");
        JsonNode sell = item.path("depth").path("sell");
        return new QuoteSnapshot(
                stockCode,
                exchangeCode,
                GatewayJsonHelper.number(item, "last_price"),
                GatewayJsonHelper.number(ohlc, "close"),
                GatewayJsonHelper.number(ohlc, "open"),
                GatewayJsonHelper.number(ohlc, "high"),
                GatewayJsonHelper.number(ohlc, "low"),
                GatewayJsonHelper.number(item, "volume"),
                firstDepthPrice(buy),
                firstDepthPrice(sell),
                parseQuoteTimestamp(item)
        );
    }

    private double firstDepthPrice(JsonNode depth) {
        if (depth == null || !depth.isArray() || depth.isEmpty()) {
            return 0;
        }
        return GatewayJsonHelper.number(depth.get(0), "price");
    }

    private ZonedDateTime parseQuoteTimestamp(JsonNode item) {
        String timestamp = GatewayJsonHelper.text(item, "timestamp");
        if (timestamp.isBlank()) {
            timestamp = GatewayJsonHelper.text(item, "last_trade_time");
        }
        if (timestamp.isBlank()) {
            return null;
        }
        return parseDateTime(timestamp);
    }

    private LocalDate parseTradeDate(JsonNode item) {
        return firstPresent(item, "fill_timestamp", "trade_timestamp", "exchange_timestamp")
                .map(this::parseDateTime)
                .map(ZonedDateTime::toLocalDate)
                .orElse(null);
    }

    private boolean shouldMergeTodayApiTrades(LocalDate today) {
        if (zerodhaTradebookService == null) {
            return true;
        }
        ZerodhaTradebookService.CoverageSummary coverage = zerodhaTradebookService.coverageSummary();
        return coverage.coveredTo() == null || coverage.coveredTo().isBefore(today);
    }

    private ZonedDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.replace(" ", "T");
        try {
            return OffsetDateTime.parse(normalized).toZonedDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(normalized, OFFSET_WITHOUT_COLON).toZonedDateTime();
            } catch (DateTimeParseException secondIgnored) {
                return LocalDateTime.parse(normalized).atZone(INDIA);
            }
        }
    }

    private String historicalQueryDateTime(LocalDate date) {
        return date.atStartOfDay().format(HISTORICAL_QUERY_FORMAT);
    }

    private Optional<String> firstPresent(JsonNode item, String... fields) {
        for (String field : fields) {
            String value = GatewayJsonHelper.text(item, field);
            if (!value.isBlank()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private tools.jackson.databind.node.ObjectNode successResponse(Map<String, Object> payload) {
        var node = objectMapper.createObjectNode();
        node.set("Success", objectMapper.valueToTree(payload));
        node.putNull("Error");
        return node;
    }

    private tools.jackson.databind.node.ObjectNode errorResponse(String message) {
        var node = objectMapper.createObjectNode();
        node.putNull("Success");
        node.put("Error", message);
        return node;
    }

    private static String required(Map<String, String> body, String key) {
        String value = body.get(key);
        if (value == null || value.isBlank()) {
            throw new BreezeApiException("Missing required Zerodha order field: " + key);
        }
        return value;
    }

    private static double nestedNumber(JsonNode node, String child, String field) {
        if (node == null || node.isMissingNode()) {
            return 0;
        }
        return GatewayJsonHelper.number(node.path(child), field);
    }

    private static String stringify(double value) {
        return String.valueOf(Math.round(value * 100.0) / 100.0);
    }

    interface RateLimiter {
        void acquire();
    }

}
