package com.broker.service;

import com.broker.exception.BreezeApiException;
import com.broker.exception.BrokerCapabilityException;
import com.broker.model.AnalysisModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

public class CompositeBrokerGateway implements BrokerDataProvider {

    private static final Logger log = LoggerFactory.getLogger(CompositeBrokerGateway.class);

    private final BrokerDataProvider breezeGatewayService;
    private final BrokerDataProvider zerodhaGatewayService;
    private final ExecutorService executorService;
    private final StockMetadataService stockMetadataService;
    private final String preferredDataBroker;
    private final String defaultOrderBroker;
    private final Map<String, String> orderBrokerById = new ConcurrentHashMap<>();

    public CompositeBrokerGateway(
            BrokerDataProvider breezeGatewayService,
            BrokerDataProvider zerodhaGatewayService,
            ExecutorService executorService,
            StockMetadataService stockMetadataService,
            String preferredDataBroker,
            String defaultOrderBroker) {
        this.breezeGatewayService = breezeGatewayService;
        this.zerodhaGatewayService = zerodhaGatewayService;
        this.executorService = executorService;
        this.stockMetadataService = stockMetadataService;
        this.preferredDataBroker = normalizeBrokerName(preferredDataBroker, "zerodha");
        this.defaultOrderBroker = normalizeBrokerName(defaultOrderBroker, "zerodha");
        log.info("Composite broker initialized: preferredDataBroker={}, defaultOrderBroker={}, alternateDataBroker={}",
                this.preferredDataBroker, this.defaultOrderBroker, providerName(alternateDataProvider()));
    }

    @Override
    public List<HoldingSnapshot> getPortfolioHoldings() {
        FetchResults<List<HoldingSnapshot>> results = fetchBoth(
                "icici holdings", breezeGatewayService::getPortfolioHoldings,
                "zerodha holdings", zerodhaGatewayService::getPortfolioHoldings,
                List.of()
        );
        List<HoldingSnapshot> breeze = results.primary();
        List<HoldingSnapshot> zerodha = results.secondary();

        Map<String, List<HoldingSnapshot>> holdingsByKey = new LinkedHashMap<>();
        for (HoldingSnapshot holding : concat(breeze, zerodha)) {
            holdingsByKey.computeIfAbsent(mergeKey(holding), ignored -> new ArrayList<>()).add(holding);
        }

        List<HoldingSnapshot> merged = new ArrayList<>();
        for (List<HoldingSnapshot> group : holdingsByKey.values()) {
            merged.add(group.size() == 1 ? group.getFirst() : mergeHoldings(group));
        }
        merged.sort(Comparator.comparing(HoldingSnapshot::stockCode));
        return merged;
    }

    @Override
    public FundsSnapshot getFunds() {
        List<FundsSnapshot> all = getAllFunds();
        return new FundsSnapshot(
                all.stream().mapToDouble(FundsSnapshot::totalBalance).sum(),
                all.stream().mapToDouble(FundsSnapshot::unallocatedBalance).sum(),
                null,
                "composite",
                Map.of()
        );
    }

    @Override
    public List<FundsSnapshot> getAllFunds() {
        FetchResults<FundsSnapshot> results = fetchBoth(
                "icici funds", breezeGatewayService::getFunds,
                "zerodha funds", zerodhaGatewayService::getFunds,
                null
        );
        List<FundsSnapshot> funds = new ArrayList<>();
        if (results.primary() != null) {
            funds.add(results.primary());
        }
        if (results.secondary() != null) {
            funds.add(results.secondary());
        }
        return funds;
    }

    @Override
    public QuoteSnapshot getQuote(String stockCode, String exchangeCode, String productType) {
        return marketDataCall(
                provider -> provider.getQuote(stockCode, exchangeCode, productType),
                "quote for " + stockCode,
                Map.of("stockCode", stockCode, "exchangeCode", exchangeCode, "productType", productType)
        );
    }

    QuoteSnapshot getQuoteForBroker(String stockCode, String exchangeCode, String productType, String broker) {
        if (broker == null || broker.isBlank()) {
            return getQuote(stockCode, exchangeCode, productType);
        }
        log.info("Routing order quote via broker={} stockCode={} exchangeCode={} productType={}",
                broker, stockCode, exchangeCode, productType);
        return brokerByName(broker).getQuote(stockCode, exchangeCode, productType);
    }

    @Override
    public Map<String, QuoteSnapshot> getQuotes(List<String> stockCodes, String exchangeCode, String productType) {
        BrokerDataProvider provider = preferredDataProvider();
        log.info("Routing batch quotes via broker={} stockCodes={} exchangeCode={} productType={}",
                providerName(provider), stockCodes, exchangeCode, productType);
        return provider.getQuotes(stockCodes, exchangeCode, productType);
    }

    @Override
    public List<HistoricalCandle> getHistoricalCharts(
            String stockCode,
            String exchangeCode,
            String productType,
            String interval,
            java.time.LocalDate fromDate,
            java.time.LocalDate toDate) {
        return marketDataCall(
                provider -> provider.getHistoricalCharts(stockCode, exchangeCode, productType, interval, fromDate, toDate),
                "historical data for " + stockCode,
                Map.of(
                        "stockCode", stockCode,
                        "exchangeCode", exchangeCode,
                        "productType", productType,
                        "interval", interval,
                        "fromDate", fromDate,
                        "toDate", toDate
                )
        );
    }

    @Override
    public List<TradeSnapshot> getTrades(java.time.LocalDate fromDate, java.time.LocalDate toDate) {
        FetchResults<List<TradeSnapshot>> results = fetchBoth(
                "icici trades", () -> breezeGatewayService.getTrades(fromDate, toDate),
                "zerodha trades", () -> zerodhaGatewayService.getTrades(fromDate, toDate),
                List.of()
        );
        List<TradeSnapshot> result = new ArrayList<>();
        result.addAll(results.primary());
        result.addAll(results.secondary());
        result.sort(Comparator.comparing(TradeSnapshot::tradeDate, Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    @Override
    public List<GttOrderSnapshot> getGttOrders() {
        FetchResults<List<GttOrderSnapshot>> results = fetchBoth(
                "icici GTT orders", breezeGatewayService::getGttOrders,
                "zerodha GTT orders", zerodhaGatewayService::getGttOrders,
                List.of()
        );
        List<GttOrderSnapshot> result = new ArrayList<>();
        result.addAll(results.primary());
        result.addAll(results.secondary());
        return result;
    }

    @Override
    public List<OptionChainSnapshot> getOptionChain(String stockCode, String expiryDate, String right) {
        return marketDataCall(
                provider -> provider.getOptionChain(stockCode, expiryDate, right),
                "option chain for " + stockCode,
                Map.of("stockCode", stockCode, "expiryDate", expiryDate, "right", right)
        );
    }

    @Override
    public JsonNode previewOrder(Map<String, String> body) {
        return routeOrderProvider(body).previewOrder(body);
    }

    @Override
    public JsonNode placeOrder(Map<String, String> body) {
        BrokerDataProvider provider = routeOrderProvider(body);
        JsonNode response = provider.placeOrder(body);
        String orderId = response.path("Success").path("order_id").asText("");
        if (!orderId.isBlank()) {
            orderBrokerById.put(orderId, providerName(provider));
        }
        return response;
    }

    @Override
    public JsonNode getOrderDetail(String exchangeCode, String orderId) {
        String broker = orderBrokerById.get(orderId);
        if (broker == null || broker.isBlank()) {
            return brokerByName(defaultOrderBroker).getOrderDetail(exchangeCode, orderId);
        }
        return brokerByName(broker).getOrderDetail(exchangeCode, orderId);
    }

    @Override
    public JsonNode placeGttOrder(Map<String, String> body) {
        return routeOrderProvider(body).placeGttOrder(body);
    }

    private BrokerDataProvider preferredDataProvider() {
        return brokerByName(preferredDataBroker);
    }

    private BrokerDataProvider alternateDataProvider() {
        return "icici".equals(preferredDataBroker) ? zerodhaGatewayService : breezeGatewayService;
    }

    private BrokerDataProvider routeOrderProvider(Map<String, String> body) {
        OrderRouting routing = resolveOrderRouting(
                body == null ? null : body.get("symbol"),
                body == null ? null : body.get("isin"),
                body == null ? null : body.get("action"),
                normalizeBrokerName(body == null ? null : body.get("broker"), null)
        );
        return brokerByName(routing.broker() == null ? defaultOrderBroker : routing.broker());
    }

    OrderRouting resolveOrderRouting(String stockCode, String isin, String action, String requestedBroker) {
        if (!"sell".equalsIgnoreCase(action == null ? "" : action.trim())) {
            return new OrderRouting(requestedBroker == null ? defaultOrderBroker : requestedBroker, null);
        }
        HoldingSnapshot iciciHolding = findHoldingForOrder(breezeGatewayService.getPortfolioHoldings(), stockCode, isin);
        HoldingSnapshot zerodhaHolding = findHoldingForOrder(zerodhaGatewayService.getPortfolioHoldings(), stockCode, isin);
        if (requestedBroker != null) {
            return new OrderRouting(requestedBroker, "icici".equals(requestedBroker) ? iciciHolding : zerodhaHolding);
        }
        boolean iciciAvailable = hasAvailableQuantity(iciciHolding);
        boolean zerodhaAvailable = hasAvailableQuantity(zerodhaHolding);
        if (iciciAvailable && zerodhaAvailable) {
            throw new BreezeApiException("Sell holding exists on both brokers. Specify broker='icici' or broker='zerodha'.");
        }
        if (iciciAvailable) {
            return new OrderRouting("icici", iciciHolding);
        }
        if (zerodhaAvailable) {
            return new OrderRouting("zerodha", zerodhaHolding);
        }
        return new OrderRouting(defaultOrderBroker, null);
    }

    private BrokerDataProvider brokerByName(String broker) {
        return "icici".equals(normalizeBrokerName(broker, "icici")) ? breezeGatewayService : zerodhaGatewayService;
    }

    private String providerName(BrokerDataProvider provider) {
        return provider == breezeGatewayService ? "icici" : "zerodha";
    }

    private <T> T marketDataCall(BrokerCall<T> call, String label, Map<String, ?> context) {
        BrokerDataProvider preferred = preferredDataProvider();
        BrokerDataProvider alternate = alternateDataProvider();
        log.info("Routing {} via preferred broker={} alternate={} context={}",
                label, providerName(preferred), providerName(alternate), context);
        try {
            T result = call.invoke(preferred);
            log.info("Completed {} via broker={}", label, providerName(preferred));
            return result;
        } catch (BrokerCapabilityException | BreezeApiException ex) {
            log.warn("Falling back to alternate broker for {} after broker={} failed: {}",
                    label, providerName(preferred), ex.getMessage());
            T result = call.invoke(alternate);
            log.info("Completed {} via fallback broker={}", label, providerName(alternate));
            return result;
        } catch (RuntimeException ex) {
            log.warn("Preferred broker failed for {} after broker={}: {}",
                    label, providerName(preferred), ex.getMessage());
            T result = call.invoke(alternate);
            log.info("Completed {} via fallback broker={}", label, providerName(alternate));
            return result;
        }
    }

    private HoldingSnapshot mergeHoldings(List<HoldingSnapshot> group) {
        double totalQuantity = group.stream().mapToDouble(HoldingSnapshot::quantity).sum();
        double weightedAveragePrice = totalQuantity == 0 ? 0 : group.stream()
                .mapToDouble(item -> item.quantity() * item.averagePrice())
                .sum() / totalQuantity;
        HoldingSnapshot preferred = group.stream()
                .filter(item -> providerNameForHolding(item).equals(preferredDataBroker))
                .findFirst()
                .orElse(group.getFirst());
        double currentMarketPrice = preferred.currentMarketPrice() > 0
                ? preferred.currentMarketPrice()
                : group.stream().mapToDouble(HoldingSnapshot::currentMarketPrice).filter(value -> value > 0).findFirst().orElse(0);
        double totalCurrentValue = group.stream()
                .mapToDouble(item -> item.quantity() * item.currentMarketPrice())
                .sum();
        double weightedChangePercentage = totalCurrentValue == 0 ? 0 : group.stream()
                .mapToDouble(item -> item.changePercentage() * item.quantity() * item.currentMarketPrice())
                .sum() / totalCurrentValue;

        return new HoldingSnapshot(
                preferred.stockCode(),
                preferred.stockName(),
                preferred.exchangeCode(),
                totalQuantity,
                weightedAveragePrice,
                currentMarketPrice,
                group.stream().mapToDouble(HoldingSnapshot::bookedProfitLoss).sum(),
                weightedChangePercentage,
                "composite",
                group.stream().map(HoldingSnapshot::isin).filter(Objects::nonNull).filter(isin -> !isin.isBlank()).findFirst().orElse(null)
        );
    }

    private String providerNameForHolding(HoldingSnapshot holding) {
        return normalizeBrokerName(holding.broker(), "");
    }

    private String mergeKey(HoldingSnapshot holding) {
        return stockMetadataService.equityJoinKey(holding.stockCode(), holding.isin());
    }

    private HoldingSnapshot findHoldingForOrder(List<HoldingSnapshot> holdings, String stockCode, String isin) {
        String symbolJoinKey = stockMetadataService.equityJoinKey(stockCode, null);
        String isinJoinKey = (isin == null || isin.isBlank()) ? null : stockMetadataService.equityJoinKey(stockCode, isin);
        return holdings.stream()
                .filter(item -> {
                    String holdingJoinKey = mergeKey(item);
                    return holdingJoinKey.equals(symbolJoinKey)
                            || (isinJoinKey != null && holdingJoinKey.equals(isinJoinKey));
                })
                .findFirst()
                .orElse(null);
    }

    private boolean hasAvailableQuantity(HoldingSnapshot holding) {
        return holding != null && holding.quantity() > 0;
    }

    private <T> T withPartialFailure(String label, Supplier<T> supplier, T fallback) {
        try {
            return supplier.get();
        } catch (RuntimeException ex) {
            log.warn("[{}] fetch failed — returning empty fallback. Cause: {} — {}",
                    label, ex.getClass().getSimpleName(), ex.getMessage());
            return fallback;
        }
    }

    private <T> FetchResults<T> fetchBoth(
            String primaryLabel,
            Supplier<T> primarySupplier,
            String secondaryLabel,
            Supplier<T> secondarySupplier,
            T fallback) {
        CompletableFuture<T> primary = CompletableFuture.supplyAsync(
                () -> withPartialFailure(primaryLabel, primarySupplier, fallback), executorService);
        CompletableFuture<T> secondary = CompletableFuture.supplyAsync(
                () -> withPartialFailure(secondaryLabel, secondarySupplier, fallback), executorService);
        return new FetchResults<>(primary.join(), secondary.join());
    }

    private static List<HoldingSnapshot> concat(List<HoldingSnapshot> first, List<HoldingSnapshot> second) {
        List<HoldingSnapshot> all = new ArrayList<>(first.size() + second.size());
        all.addAll(first);
        all.addAll(second);
        return all;
    }

    private static String normalizeBrokerName(String broker, String fallback) {
        if (broker == null || broker.isBlank()) {
            return fallback;
        }
        String normalized = broker.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("icici") && !normalized.equals("zerodha")) {
            throw new BreezeApiException("broker must be 'icici' or 'zerodha'");
        }
        return normalized;
    }

    @FunctionalInterface
    private interface BrokerCall<T> {
        T invoke(BrokerDataProvider provider);
    }

    private record FetchResults<T>(T primary, T secondary) {
    }

    record OrderRouting(String broker, HoldingSnapshot holding) {
    }
}
