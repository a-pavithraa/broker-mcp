package com.broker.analysis;

import com.broker.exception.BrokerApiException;
import com.broker.gateway.BrokerDataProvider;
import com.broker.gateway.CompositeBrokerGateway;
import com.broker.model.AnalysisModels.*;
import com.broker.reference.StockMetadataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

class OrderManagementService {

    private static final Logger log = LoggerFactory.getLogger(OrderManagementService.class);
    private static final Set<String> EXCLUDED_STOP_LOSS_CODES = Set.of("GOLDEX", "SBIGOL");

    private final BrokerDataProvider dataProvider;
    private final StockMetadataService stockMetadataService;
    private final ObjectMapper objectMapper;
    private final boolean tradingEnabled;
    private final double maxOrderValue;
    private final Clock clock;
    private final Path tradeLogPath;

    OrderManagementService(
            BrokerDataProvider dataProvider,
            StockMetadataService stockMetadataService,
            ObjectMapper objectMapper,
            boolean tradingEnabled,
            double maxOrderValue,
            Clock clock,
            Path tradeLogPath
    ) {
        this.dataProvider = dataProvider;
        this.stockMetadataService = stockMetadataService;
        this.objectMapper = objectMapper;
        this.tradingEnabled = tradingEnabled;
        this.maxOrderValue = maxOrderValue;
        this.clock = clock;
        this.tradeLogPath = tradeLogPath;
    }

    Map<String, Object> buildOrderPreviewPayload(
            String stockCode,
            String action,
            int quantity,
            String orderType,
            Double price,
            String exchange,
            OrderSession session
    ) {
        return buildOrderPreviewPayload(stockCode, action, quantity, orderType, price, exchange, null, session);
    }

    Map<String, Object> buildOrderPreviewPayload(
            String stockCode,
            String action,
            int quantity,
            String orderType,
            Double price,
            String exchange,
            String broker,
            OrderSession session
    ) {
        String normalizedExchange = normalizeExchangeSingle(exchange);
        String normalizedBroker = normalizeBroker(broker);
        ValidatedOrder validatedOrder = validateOrderInputs(stockCode, action, quantity, orderType, price);
        OrderAssessment assessment = assessOrder(validatedOrder, quantity, normalizedExchange, normalizedBroker);
        return buildOrderPreviewPayload(assessment, quantity, session);
    }

    Map<String, Object> buildOrderPreviewPayload(
            OrderAssessment assessment,
            int quantity,
            OrderSession session
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("market_session", session.marketSession());
        result.put("order_summary", Map.of(
                "stock", assessment.order().stock().name(),
                "action", assessment.order().action(),
                "quantity", quantity,
                "price", round2(assessment.effectivePrice()),
                "gross_value", moneyValue(assessment.grossValue())
        ));
        result.put("charges", chargeBreakdown(assessment.previewResponse()));
        boolean isSell = "sell".equals(assessment.order().action());
        result.put("net_amount", moneyValue(isSell
                ? assessment.grossValue().subtract(assessment.totalCharges())
                : assessment.grossValue().add(assessment.totalCharges())));

        if (isSell) {
            result.put("position_impact", Map.of(
                "avg_buy_price", round2(assessment.averageBuyPrice()),
                    "realized_pnl", moneyValue(money(assessment.effectivePrice())
                            .subtract(money(assessment.averageBuyPrice()))
                            .multiply(BigDecimal.valueOf(quantity))),
                    "loss_type", assessment.holding() == null ? "unknown" : assessment.averageBuyPrice() > assessment.effectivePrice() ? "loss" : "gain"
            ));
        } else {
            result.put("position_impact", null);
        }
        List<String> validationMessages = new ArrayList<>();
        if (!assessment.sufficientQuantity()) {
            validationMessages.add("Insufficient quantity available for this sell order.");
        }
        if (!assessment.sufficientFunds()) {
            validationMessages.add("Insufficient available funds for this buy order.");
        }
        if (!session.marketOpen()) {
            validationMessages.add("Market is currently closed. Quotes are from the last completed trading session and live cash execution is unavailable.");
        }
        if (validationMessages.isEmpty()) {
            validationMessages.add("Local pre-checks passed.");
        }
        result.put("validation", Map.of(
                "sufficient_quantity", assessment.sufficientQuantity(),
                "sufficient_funds", assessment.sufficientFunds(),
                "market_open", session.marketOpen(),
                "ready_to_place", session.marketOpen() && assessment.sufficientQuantity() && assessment.sufficientFunds(),
                "messages", validationMessages
        ));
        return result;
    }

    Map<String, Object> executeTrade(
            String stockCode,
            String action,
            int quantity,
            String orderType,
            Double price,
            boolean confirmed,
            String exchange,
            String broker,
            OrderSession session
    ) {
        if (!tradingEnabled) {
            return Map.of(
                    "status", "DISABLED",
                    "message", "Trading is disabled. Set broker.trading.enabled=true to enable order placement.",
                    "market_session", session.marketSession()
            );
        }

        String normalizedExchange = normalizeExchangeSingle(exchange);
        String normalizedBroker = normalizeBroker(broker);
        ValidatedOrder validatedOrder = validateOrderInputs(stockCode, action, quantity, orderType, price);
        OrderAssessment assessment = assessOrder(validatedOrder, quantity, normalizedExchange, normalizedBroker);
        if (!session.marketOpen()) {
            return Map.of(
                    "status", "REJECTED",
                    "message", "Market is currently closed. This tool only supports regular-session cash orders.",
                    "market_session", session.marketSession(),
                    "order", buildOrderPreviewPayload(assessment, quantity, session)
            );
        }
        if (assessment.grossValue().compareTo(money(maxOrderValue)) > 0) {
            return Map.of(
                    "status", "REJECTED",
                    "message", "Order value %.2f exceeds maximum allowed %.2f. Adjust quantity or contact support.".formatted(
                            moneyValue(assessment.grossValue()),
                            moneyValue(money(maxOrderValue))
                    ),
                    "market_session", session.marketSession(),
                    "order", buildOrderPreviewPayload(assessment, quantity, session)
            );
        }
        if (!confirmed) {
            return Map.of(
                    "status", "PREVIEW",
                    "message", "Review and confirm to execute",
                    "market_session", session.marketSession(),
                    "order", buildOrderPreviewPayload(assessment, quantity, session)
            );
        }
        if (!assessment.sufficientQuantity()) {
            return Map.of(
                    "status", "REJECTED",
                    "message", "Insufficient quantity available to place this sell order.",
                    "market_session", session.marketSession(),
                    "order", buildOrderPreviewPayload(assessment, quantity, session)
            );
        }
        if (!assessment.sufficientFunds()) {
            return Map.of(
                    "status", "REJECTED",
                    "message", "Insufficient available funds to place this buy order.",
                    "market_session", session.marketSession(),
                    "order", buildOrderPreviewPayload(assessment, quantity, session)
            );
        }

        JsonNode orderResponse = dataProvider.placeOrder(buildExecutionBody(
                validatedOrder,
                assessment.effectivePrice(),
                normalizedExchange,
                assessment.routeBroker()
        ));
        Map<String, Object> success = extractSuccess(orderResponse);
        String orderId = String.valueOf(success.getOrDefault("order_id", ""));
        Map<String, Object> detail = extractSuccess(dataProvider.getOrderDetail(normalizedExchange, orderId));
        logTradeExecution(validatedOrder.stock().nseSymbol(), action, quantity, assessment.effectivePrice(), orderId);
        String brokerStatus = orderBrokerStatus(detail, success);

        return Map.of(
                "status", brokerStatus,
                "order_id", orderId,
                "stock", validatedOrder.stock().nseSymbol(),
                "action", action.toLowerCase(Locale.ROOT),
                "quantity", quantity,
                "execution_price", round2(assessment.effectivePrice()),
                "message", detail.getOrDefault("message", success.getOrDefault("message", "Order submitted")),
                "market_session", session.marketSession(),
                "broker_response", detail
        );
    }

    Map<String, Object> setStopLosses(
            List<String> stockCodes,
            double stopLossPct,
            boolean confirmed,
            String exchange,
            String broker,
            OrderSession session
    ) {
        if (!tradingEnabled) {
            return Map.of(
                    "status", "DISABLED",
                    "message", "Trading is disabled. Set broker.trading.enabled=true to enable GTT placement.",
                    "market_session", session.marketSession()
            );
        }
        if (stopLossPct <= 0 || stopLossPct >= 100) {
            throw new BrokerApiException("stopLossPct must be between 0 and 100");
        }

        String normalizedExchange = normalizeExchangeSingle(exchange);
        String normalizedBroker = normalizeBroker(broker);
        List<HoldingSnapshot> holdings = dataProvider.getPortfolioHoldings();
        Set<String> requestedCodes = stockCodes == null ? Set.of() : stockCodes.stream()
                .filter(Objects::nonNull)
                .map(this::equityJoinKey)
                .collect(Collectors.toSet());
        List<HoldingSnapshot> targetHoldings = holdings.stream()
                .filter(holding -> requestedCodes.isEmpty()
                        || requestedCodes.contains(equityJoinKey(holding.stockCode(), holding.isin())))
                .toList();
        List<GttOrderSnapshot> existingOrders = dataProvider.getGttOrders();
        Set<String> existingCodes = existingOrders.stream()
                .map(order -> equityJoinKey(order.stockCode()))
                .collect(Collectors.toSet());

        List<String> skippedHasGtt = new ArrayList<>();
        List<String> skippedEtfMf = new ArrayList<>();
        List<String> skippedNoQuote = new ArrayList<>();
        List<HoldingSnapshot> eligibleHoldings = new ArrayList<>();
        for (HoldingSnapshot holding : targetHoldings) {
            String displayCode = displaySymbol(holding.stockCode());
            String joinKey = equityJoinKey(holding.stockCode(), holding.isin());
            if (EXCLUDED_STOP_LOSS_CODES.contains(displayCode.toUpperCase(Locale.ROOT))) {
                skippedEtfMf.add(displayCode);
            } else if (existingCodes.contains(joinKey)) {
                skippedHasGtt.add(displayCode);
            } else {
                eligibleHoldings.add(holding);
            }
        }

        List<String> eligibleCodes = eligibleHoldings.stream().map(holding -> displaySymbol(holding.stockCode())).toList();
        Map<String, QuoteSnapshot> quotes = dataProvider.getQuotes(eligibleCodes, normalizedExchange, "cash");

        List<Map<String, Object>> preview = new ArrayList<>();
        List<Map<String, Object>> placedOrders = new ArrayList<>();
        List<Map<String, Object>> failedOrders = new ArrayList<>();
        for (HoldingSnapshot holding : eligibleHoldings) {
            String displayCode = displaySymbol(holding.stockCode());
            QuoteSnapshot quote = quotes.get(displayCode);
            if (quote == null) {
                skippedNoQuote.add(displayCode);
                continue;
            }
            double triggerPrice = round2(quote.ltp() * (1 - (stopLossPct / 100)));
            Map<String, Object> previewItem = Map.of(
                    "stock", displayCode,
                    "qty", round2(holding.quantity()),
                    "current_price", round2(quote.ltp()),
                    "trigger_price", triggerPrice,
                    "potential_loss_if_hit", round2((quote.ltp() - triggerPrice) * holding.quantity())
            );
            preview.add(previewItem);
            if (confirmed) {
                try {
                    Map<String, String> request = new HashMap<>();
                    request.put("symbol", displayCode);
                    request.put("exchange", normalizedExchange);
                    request.put("product", "cash");
                    request.put("action", "sell");
                    request.put("order_type", "market");
                    request.put("quantity", String.valueOf((int) holding.quantity()));
                    request.put("price", "0");
                    request.put("trigger_price", String.valueOf(triggerPrice));
                    request.put("limit_price", String.valueOf(triggerPrice));
                    if (normalizedBroker != null) {
                        request.put("broker", normalizedBroker);
                    }
                    Map<String, Object> success = extractSuccess(dataProvider.placeGttOrder(request));
                    placedOrders.add(Map.of(
                            "stock", displayCode,
                            "order_id", String.valueOf(success.getOrDefault("order_id", "")),
                            "trigger_price", triggerPrice
                    ));
                } catch (Exception e) {
                    failedOrders.add(Map.of(
                            "stock", displayCode,
                            "trigger_price", triggerPrice,
                            "error", e.getMessage()
                    ));
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("market_session", session.marketSession());
        result.put("status", stopLossStatus(confirmed, placedOrders, failedOrders, preview));
        result.put("stop_losses", preview);
        result.put("placed_orders", placedOrders);
        result.put("failed_orders", failedOrders);
        result.put("skipped_has_gtt", skippedHasGtt);
        result.put("skipped_etf_mf", skippedEtfMf);
        result.put("skipped_no_quote", skippedNoQuote);
        result.put("total_orders", preview.size());
        return result;
    }

    ValidatedOrder validateOrderInputs(String stockCode, String action, int quantity, String orderType, Double price) {
        if (quantity <= 0) {
            throw new BrokerApiException("quantity must be greater than zero");
        }
        String normalizedAction = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("buy", "sell").contains(normalizedAction)) {
            throw new BrokerApiException("action must be 'buy' or 'sell'");
        }
        String normalizedOrderType = (orderType == null || orderType.isBlank()) ? "market" : orderType.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("market", "limit").contains(normalizedOrderType)) {
            throw new BrokerApiException("orderType must be 'market' or 'limit'");
        }
        if ("limit".equals(normalizedOrderType) && (price == null || price <= 0)) {
            throw new BrokerApiException("price is required for limit orders");
        }
        return new ValidatedOrder(stockMetadataService.resolve(stockCode), normalizedAction, normalizedOrderType, price, quantity);
    }

    Map<String, String> buildPreviewBody(ValidatedOrder order, double price, String exchangeCode, String broker, HoldingSnapshot holding) {
        Map<String, String> body = new HashMap<>();
        body.put("symbol", order.stock().nseSymbol());
        body.put("exchange", exchangeCode);
        body.put("product", "cash");
        body.put("order_type", order.orderType());
        body.put("price", String.valueOf(price));
        body.put("action", order.action());
        body.put("quantity", String.valueOf(order.quantity()));
        body.put("specialflag", "N");
        if ("sell".equals(order.action()) && holding != null && holding.averagePrice() > 0) {
            body.put("average_price", String.valueOf(holding.averagePrice()));
        }
        if (broker != null) {
            body.put("broker", broker);
        }
        return body;
    }

    Map<String, String> buildExecutionBody(ValidatedOrder order, double price, String exchangeCode, String broker) {
        Map<String, String> body = new HashMap<>();
        body.put("symbol", order.stock().nseSymbol());
        body.put("exchange", exchangeCode);
        body.put("product", "cash");
        body.put("action", order.action());
        body.put("order_type", order.orderType());
        body.put("quantity", String.valueOf(order.quantity()));
        body.put("price", String.valueOf(price));
        body.put("validity", "day");
        if (broker != null) {
            body.put("broker", broker);
        }
        return body;
    }

    String normalizeExchangeSingle(String exchange) {
        if (exchange == null || exchange.isBlank()) {
            return "NSE";
        }
        String upper = exchange.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("NSE", "BSE").contains(upper)) {
            throw new BrokerApiException("exchange must be 'NSE' or 'BSE'");
        }
        return upper;
    }

    String normalizeBroker(String broker) {
        if (broker == null || broker.isBlank()) {
            return null;
        }
        String normalized = broker.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("icici", "zerodha").contains(normalized)) {
            throw new BrokerApiException("broker must be 'icici' or 'zerodha'");
        }
        return normalized;
    }

    Map<String, Object> extractSuccess(JsonNode response) {
        JsonNode success = response.get("Success");
        if (success == null || success.isNull()) {
            JsonNode error = response.get("Error");
            throw new BrokerApiException(error == null ? "Unexpected response from Breeze API" : error.asText());
        }
        return objectMapper.convertValue(success, Map.class);
    }

    OrderAssessment assessOrder(ValidatedOrder validatedOrder, int quantity, String exchangeCode, String requestedBroker) {
        OrderRoute route = resolveOrderRoute(validatedOrder, requestedBroker);
        QuoteSnapshot quote = resolveQuote(validatedOrder, exchangeCode, route.broker());
        FundsSnapshot funds = resolveFunds(route.broker());

        double effectivePrice = validatedOrder.orderType().equals("market") ? quote.ltp() : validatedOrder.price();
        HoldingSnapshot holding = route.holding();
        double avgPrice = holding == null ? 0 : holding.averagePrice();
        boolean sufficientQuantity = !"sell".equals(validatedOrder.action()) || (holding != null && holding.quantity() >= quantity);
        Map<String, Object> previewResponse = extractSuccess(
                dataProvider.previewOrder(buildPreviewBody(validatedOrder, effectivePrice, exchangeCode, route.broker(), holding))
        );
        BigDecimal totalCharges = sumCharges(previewResponse);
        BigDecimal grossValue = money(effectivePrice).multiply(BigDecimal.valueOf(quantity));
        boolean sufficientFunds = !"buy".equals(validatedOrder.action())
                || money(funds.unallocatedBalance()).compareTo(grossValue.add(totalCharges)) >= 0;
        return new OrderAssessment(validatedOrder, quote, holding, funds, effectivePrice, avgPrice,
                previewResponse, grossValue, totalCharges, sufficientQuantity, sufficientFunds, route.broker());
    }

    OrderRoute resolveOrderRoute(ValidatedOrder validatedOrder, String requestedBroker) {
        if (dataProvider instanceof CompositeBrokerGateway compositeGateway) {
            CompositeBrokerGateway.OrderRouting routing = compositeGateway.resolveOrderRouting(
                    validatedOrder.stock().nseSymbol(),
                    validatedOrder.stock().isin(),
                    validatedOrder.action(),
                    requestedBroker
            );
            return new OrderRoute(routing.broker(), routing.holding());
        }
        if ("sell".equals(validatedOrder.action())) {
            return new OrderRoute(requestedBroker, findHolding(dataProvider.getPortfolioHoldings(), validatedOrder.stock().nseSymbol()));
        }
        return new OrderRoute(requestedBroker, null);
    }

    QuoteSnapshot resolveQuote(ValidatedOrder validatedOrder, String exchangeCode, String broker) {
        if (!"market".equals(validatedOrder.orderType())) {
            return null;
        }
        if (dataProvider instanceof CompositeBrokerGateway compositeGateway) {
            return compositeGateway.getQuoteForBroker(validatedOrder.stock().nseSymbol(), exchangeCode, "cash", broker);
        }
        return dataProvider.getQuote(validatedOrder.stock().nseSymbol(), exchangeCode, "cash");
    }

    FundsSnapshot resolveFunds(String broker) {
        List<FundsSnapshot> funds = dataProvider.getAllFunds();
        if (broker == null || broker.isBlank()) {
            return aggregateFunds(funds);
        }
        List<FundsSnapshot> matchingFunds = funds.stream()
                .filter(Objects::nonNull)
                .filter(item -> broker.equalsIgnoreCase(item.broker()))
                .toList();
        if (!matchingFunds.isEmpty()) {
            return aggregateFunds(matchingFunds);
        }
        return aggregateFunds(funds);
    }

    FundsSnapshot aggregateFunds(List<FundsSnapshot> funds) {
        if (funds == null || funds.isEmpty()) {
            return new FundsSnapshot(0, 0, null, "aggregate", Map.of());
        }
        if (funds.size() == 1) {
            return funds.getFirst();
        }
        return new FundsSnapshot(
                funds.stream().mapToDouble(FundsSnapshot::totalBalance).sum(),
                totalUnallocatedBalance(funds),
                null,
                "aggregate",
                Map.of()
        );
    }

    double totalUnallocatedBalance(List<FundsSnapshot> funds) {
        if (funds == null || funds.isEmpty()) {
            return 0;
        }
        return funds.stream().mapToDouble(FundsSnapshot::unallocatedBalance).sum();
    }

    Map<String, Object> chargeBreakdown(Map<String, Object> previewResponse) {
        Map<String, Object> charges = new LinkedHashMap<>();
        charges.put("brokerage", moneyValue(money(previewResponse.get("brokerage"))));
        charges.put("stt", moneyValue(money(previewResponse.get("stt"))));
        charges.put("exchange_charges", moneyValue(money(previewResponse.get("exchange_turnover_charges"))));
        charges.put("stamp_duty", moneyValue(money(previewResponse.get("stamp_duty"))));
        charges.put("sebi_charges", moneyValue(money(previewResponse.get("sebi_charges"))));
        charges.put("gst", moneyValue(money(previewResponse.get("gst"))));
        charges.put("total_charges", moneyValue(sumCharges(previewResponse)));
        return charges;
    }

    BigDecimal sumCharges(Map<String, Object> previewResponse) {
        return money(previewResponse.get("brokerage"))
                .add(money(previewResponse.get("stt")))
                .add(money(previewResponse.get("exchange_turnover_charges")))
                .add(money(previewResponse.get("stamp_duty")))
                .add(money(previewResponse.get("sebi_charges")))
                .add(money(previewResponse.get("gst")));
    }

    String stopLossStatus(boolean confirmed,
                          List<Map<String, Object>> placedOrders,
                          List<Map<String, Object>> failedOrders,
                          List<Map<String, Object>> preview) {
        if (!confirmed) {
            return "PREVIEW";
        }
        if (!placedOrders.isEmpty() && failedOrders.isEmpty()) {
            return "EXECUTED";
        }
        if (!placedOrders.isEmpty()) {
            return "PARTIAL";
        }
        if (!failedOrders.isEmpty()) {
            return "REJECTED";
        }
        return preview.isEmpty() ? "NO_ACTION" : "PREVIEW";
    }

    String orderBrokerStatus(Map<String, Object> detail, Map<String, Object> success) {
        String brokerText = java.util.stream.Stream.of(
                        detail.get("status"),
                        detail.get("order_status"),
                        detail.get("message"),
                        success.get("status"),
                        success.get("order_status"),
                        success.get("message"))
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .findFirst()
                .orElse("Order placed");
        String normalized = brokerText.toLowerCase(Locale.ROOT);
        if (normalized.contains("reject") || normalized.contains("fail") || normalized.contains("cancel")) {
            return "REJECTED";
        }
        if (normalized.contains("execut") || normalized.contains("fill") || normalized.contains("complete")) {
            return "EXECUTED";
        }
        return "PLACED";
    }

    String equityJoinKey(String stockCode) {
        return stockMetadataService.equityJoinKey(stockCode, null);
    }

    String equityJoinKey(String stockCode, String isin) {
        return stockMetadataService.equityJoinKey(stockCode, isin);
    }

    String displaySymbol(String stockCode) {
        return stockMetadataService.resolve(stockCode).nseSymbol();
    }

    HoldingSnapshot findHolding(List<HoldingSnapshot> holdings, String code) {
        String joinKey = equityJoinKey(code);
        return holdings.stream()
                .filter(item -> equityJoinKey(item.stockCode(), item.isin()).equals(joinKey))
                .findFirst()
                .orElse(null);
    }

    void logTradeExecution(String stockCode, String action, int quantity, double price, String orderId) {
        try {
            Files.createDirectories(tradeLogPath.getParent());
            String line = "%s %s %s qty=%d price=%.2f orderId=%s%n".formatted(
                    ZonedDateTime.now(clock), stockCode, action, quantity, price, orderId);
            Files.writeString(tradeLogPath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new BrokerApiException("Trade was placed but logging failed", e);
        }
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private BigDecimal money(double value) {
        return BigDecimal.valueOf(value);
    }

    private BigDecimal money(Object value) {
        switch (value) {
            case null -> {
                return BigDecimal.ZERO;
            }
            case BigDecimal bigDecimal -> {
                return bigDecimal;
            }
            case Number number -> {
                return BigDecimal.valueOf(number.doubleValue());
            }
            default -> {
            }
        }
        try {
            return new BigDecimal(String.valueOf(value).replace(",", ""));
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private double moneyValue(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    record ValidatedOrder(ResolvedStock stock, String action, String orderType, Double price, int quantity) {
    }

    record OrderAssessment(
            ValidatedOrder order,
            QuoteSnapshot quote,
            HoldingSnapshot holding,
            FundsSnapshot funds,
            double effectivePrice,
            double averageBuyPrice,
            Map<String, Object> previewResponse,
            BigDecimal grossValue,
            BigDecimal totalCharges,
            boolean sufficientQuantity,
            boolean sufficientFunds,
            String routeBroker
    ) {
    }

    record OrderRoute(String broker, HoldingSnapshot holding) {
    }

    record OrderSession(Map<String, Object> marketSession, boolean marketOpen) {
    }
}
