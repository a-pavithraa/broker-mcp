package com.broker.analysis;

import com.broker.exception.BrokerApiException;
import com.broker.gateway.BrokerDataProvider;
import com.broker.model.AnalysisModels.*;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

@Service
public class CompoundToolService {

    private static final Logger log = LoggerFactory.getLogger(CompoundToolService.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final BrokerDataProvider dataProvider;
    private final ObjectMapper objectMapper;
    private final int tradeHistoryYears;
    private final Clock clock;
    private final ExecutorService analysisExecutor;
    private final boolean ownsAnalysisExecutor;
    private final TaxHarvestService taxHarvestService;
    private final PortfolioAnalysisService portfolioAnalysisService;
    private final OrderManagementService orderManagementService;
    private final MarketDataService marketDataService;

    @Autowired
    public CompoundToolService(
            BrokerDataProvider dataProvider,
            ObjectMapper objectMapper,
            @Value("${broker.trade-history-years:5}") int tradeHistoryYears,
            Clock indiaClock,
            @Qualifier("brokerIoExecutor") ExecutorService analysisExecutor,
            TaxHarvestService taxHarvestService,
            PortfolioAnalysisService portfolioAnalysisService,
            OrderManagementService orderManagementService,
            MarketDataService marketDataService
    ) {
        this(new Dependencies(
                dataProvider,
                objectMapper,
                tradeHistoryYears,
                indiaClock,
                analysisExecutor,
                false,
                taxHarvestService,
                portfolioAnalysisService,
                orderManagementService,
                marketDataService
        ));
    }

    protected CompoundToolService(Dependencies dependencies) {
        this.dataProvider = dependencies.dataProvider();
        this.objectMapper = dependencies.objectMapper();
        this.tradeHistoryYears = dependencies.tradeHistoryYears();
        this.clock = dependencies.clock().withZone(INDIA);
        this.analysisExecutor = dependencies.analysisExecutor();
        this.ownsAnalysisExecutor = dependencies.ownsAnalysisExecutor();
        this.taxHarvestService = dependencies.taxHarvestService();
        this.portfolioAnalysisService = dependencies.portfolioAnalysisService();
        this.orderManagementService = dependencies.orderManagementService();
        this.marketDataService = dependencies.marketDataService();
    }

    public String portfolioSnapshot() {
        CompletableFuture<List<HoldingSnapshot>> holdingsFuture = submit(dataProvider::getPortfolioHoldings);
        CompletableFuture<List<FundsSnapshot>> fundsFuture = submit(dataProvider::getAllFunds);
        List<HoldingSnapshot> holdings = joinUnchecked(holdingsFuture);
        List<FundsSnapshot> funds = joinUnchecked(fundsFuture);
        MarketDataService.MarketSession marketSession = marketDataService.currentMarketSession();
        return toJson(portfolioAnalysisService.buildPortfolioSnapshot(holdings, funds, marketSession.toMap()));
    }

    public String stockCheckup(String stockCode, String exchange) {
        return toJson(marketDataService.stockCheckup(stockCode, exchange));
    }

    public String portfolioHealth() {
        LocalDate today = LocalDate.now(clock);
        CompletableFuture<List<HoldingSnapshot>> holdingsFuture = submit(dataProvider::getPortfolioHoldings);
        CompletableFuture<List<FundsSnapshot>> fundsFuture = submit(dataProvider::getAllFunds);
        CompletableFuture<List<HistoricalCandle>> niftyHistoryFuture =
                submit(() -> dataProvider.getHistoricalCharts("NIFTY", "NSE", "cash", "day", today.minusYears(1), today));
        CompletableFuture<List<GttOrderSnapshot>> gttOrdersFuture = submit(this::safeGetGttOrders);

        List<HoldingSnapshot> holdings = joinUnchecked(holdingsFuture);
        List<FundsSnapshot> funds = joinUnchecked(fundsFuture);
        List<HistoricalCandle> niftyHistory = joinUnchecked(niftyHistoryFuture);
        List<GttOrderSnapshot> gttOrders = joinUnchecked(gttOrdersFuture);
        MarketDataService.MarketSession marketSession = marketDataService.currentMarketSession();
        return toJson(portfolioAnalysisService.buildPortfolioHealth(
                holdings,
                funds,
                niftyHistory,
                gttOrders,
                marketSession.toMap(),
                marketSession.isOpen()
        ));
    }

    public String taxHarvestReport() {
        LocalDate today = LocalDate.now(clock);
        CompletableFuture<List<HoldingSnapshot>> holdingsFuture = submit(dataProvider::getPortfolioHoldings);
        CompletableFuture<List<TradeSnapshot>> tradesFuture = submit(() -> dataProvider.getTrades(today.minusYears(tradeHistoryYears), today));
        List<HoldingSnapshot> holdings = joinUnchecked(holdingsFuture);
        List<TradeSnapshot> apiTrades = joinUnchecked(tradesFuture);
        return toJson(taxHarvestService.buildTaxHarvestReport(holdings, apiTrades, today));
    }

    public String orderPreview(String stockCode, String action, int quantity, String orderType, Double price, String exchange) {
        return orderPreview(stockCode, action, quantity, orderType, price, exchange, null);
    }

    public String orderPreview(String stockCode, String action, int quantity, String orderType, Double price, String exchange, String broker) {
        MarketDataService.MarketSession marketSession = marketDataService.currentMarketSession();
        return toJson(orderManagementService.buildOrderPreviewPayload(
                stockCode,
                action,
                quantity,
                orderType,
                price,
                exchange,
                broker,
                new OrderManagementService.OrderSession(marketSession.toMap(), marketSession.isOpen())
        ));
    }

    public String executeTrade(String stockCode, String action, int quantity, String orderType, Double price, boolean confirmed, String exchange) {
        return executeTrade(stockCode, action, quantity, orderType, price, confirmed, exchange, null);
    }

    public String executeTrade(String stockCode, String action, int quantity, String orderType, Double price, boolean confirmed, String exchange, String broker) {
        MarketDataService.MarketSession marketSession = marketDataService.currentMarketSession();
        return toJson(orderManagementService.executeTrade(
                stockCode,
                action,
                quantity,
                orderType,
                price,
                confirmed,
                exchange,
                broker,
                new OrderManagementService.OrderSession(marketSession.toMap(), marketSession.isOpen())
        ));
    }

    public String setStopLosses(List<String> stockCodes, double stopLossPct, boolean confirmed, String exchange) {
        return setStopLosses(stockCodes, stopLossPct, confirmed, exchange, null);
    }

    public String setStopLosses(List<String> stockCodes, double stopLossPct, boolean confirmed, String exchange, String broker) {
        MarketDataService.MarketSession marketSession = marketDataService.currentMarketSession();
        return toJson(orderManagementService.setStopLosses(
                stockCodes,
                stopLossPct,
                confirmed,
                exchange,
                broker,
                new OrderManagementService.OrderSession(marketSession.toMap(), marketSession.isOpen())
        ));
    }

    public String marketPulse() {
        return toJson(marketDataService.marketPulse());
    }

    @PreDestroy
    void shutdownAnalysisExecutor() {
        if (ownsAnalysisExecutor) {
            analysisExecutor.shutdown();
        }
    }

    boolean isAnalysisExecutorShutdown() {
        return analysisExecutor.isShutdown();
    }

    private <T> CompletableFuture<T> submit(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, analysisExecutor);
    }

    private List<GttOrderSnapshot> safeGetGttOrders() {
        try {
            return dataProvider.getGttOrders();
        } catch (Exception e) {
            log.warn("GTT orders unavailable: {}", e.getMessage());
            return null;
        }
    }

    private <T> T joinUnchecked(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new BrokerApiException("Concurrent analysis call failed", cause);
        }
    }

    private String toJson(Object value) {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    public record Dependencies(
            BrokerDataProvider dataProvider,
            ObjectMapper objectMapper,
            int tradeHistoryYears,
            Clock clock,
            ExecutorService analysisExecutor,
            boolean ownsAnalysisExecutor,
            TaxHarvestService taxHarvestService,
            PortfolioAnalysisService portfolioAnalysisService,
            OrderManagementService orderManagementService,
            MarketDataService marketDataService
    ) {
    }
}
