package com.broker.service;

import com.broker.exception.BreezeApiException;
import com.broker.model.AnalysisModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final BrokerDataProvider dataProvider;
    private final StockMetadataService stockMetadataService;
    private final Clock clock;
    private final ExecutorService analysisExecutor;

    MarketDataService(
            BrokerDataProvider dataProvider,
            StockMetadataService stockMetadataService,
            Clock clock,
            ExecutorService analysisExecutor
    ) {
        this.dataProvider = dataProvider;
        this.stockMetadataService = stockMetadataService;
        this.clock = clock;
        this.analysisExecutor = analysisExecutor;
    }

    Map<String, Object> stockCheckup(String stockCode, String exchange) {
        String normalizedExchange = normalizeExchange(exchange);
        ResolvedStock resolved = stockMetadataService.resolve(stockCode);
        MarketSession marketSession = currentMarketSession();
        String primaryExchange = "BOTH".equals(normalizedExchange) ? "NSE" : normalizedExchange;
        LocalDate today = LocalDate.now(clock);

        CompletableFuture<List<HoldingSnapshot>> holdingsFuture = submit(dataProvider::getPortfolioHoldings);
        CompletableFuture<QuoteSnapshot> primaryQuoteFuture =
                submit(() -> dataProvider.getQuote(resolved.nseSymbol(), primaryExchange, "cash"));
        CompletableFuture<List<HistoricalCandle>> sixMonthHistoryFuture =
                submit(() -> dataProvider.getHistoricalCharts(resolved.nseSymbol(), primaryExchange, "cash", "day", today.minusMonths(6), today));
        CompletableFuture<List<HistoricalCandle>> oneYearHistoryFuture =
                submit(() -> dataProvider.getHistoricalCharts(resolved.nseSymbol(), primaryExchange, "cash", "day", today.minusYears(1), today));
        CompletableFuture<List<HistoricalCandle>> alternateOneYearHistoryFuture = "BOTH".equals(normalizedExchange)
                ? submit(() -> dataProvider.getHistoricalCharts(resolved.nseSymbol(), "BSE", "cash", "day", today.minusYears(1), today))
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture<QuoteSnapshot> bseQuoteFuture = "BOTH".equals(normalizedExchange)
                ? submit(() -> dataProvider.getQuote(resolved.nseSymbol(), "BSE", "cash"))
                : CompletableFuture.completedFuture(null);

        List<HoldingSnapshot> holdings = joinUnchecked(holdingsFuture);
        HoldingSnapshot holding = findHolding(holdings, resolved.nseSymbol(), resolved.isin());
        QuoteSnapshot primaryQuote = joinUnchecked(primaryQuoteFuture);
        double portfolioTotalValue = holdings.stream()
                .mapToDouble(item -> item.quantity() * item.currentMarketPrice())
                .sum();
        List<HistoricalCandle> sixMonthHistory = joinUnchecked(sixMonthHistoryFuture);
        List<HistoricalCandle> oneYearHistory = joinUnchecked(oneYearHistoryFuture);
        List<HistoricalCandle> alternateOneYearHistory = joinUnchecked(alternateOneYearHistoryFuture);

        double currentPrice = primaryQuote.ltp();
        double week52High = oneYearHistory.stream().mapToDouble(HistoricalCandle::high).max().orElse(currentPrice);
        double week52Low = oneYearHistory.stream().mapToDouble(HistoricalCandle::low).min().orElse(currentPrice);
        double sma50 = movingAverage(oneYearHistory, 50);
        double sma200 = movingAverage(oneYearHistory, 200);
        double oneMonthReturn = trailingReturn(oneYearHistory, 21);
        double threeMonthReturn = trailingReturn(oneYearHistory, 63);
        double sixMonthReturn = trailingReturn(oneYearHistory, 126);
        double volume20 = averageVolume(oneYearHistory, 20);
        double volume60 = averageVolume(oneYearHistory, 60);
        String volumeTrend = volume20 > volume60 ? "Rising" : volume20 < volume60 ? "Cooling" : "Stable";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("market_session", marketSession.toMap());
        result.put("stock", Map.of(
                "code", resolved.nseSymbol(),
                "name", resolved.name(),
                "exchange", normalizedExchange
        ));
        result.put("your_position", buildPositionBlock(holding, primaryQuote, portfolioTotalValue));

        if ("BOTH".equals(normalizedExchange)) {
            QuoteSnapshot bseQuote = joinUnchecked(bseQuoteFuture);
            double bseWeek52High = alternateOneYearHistory.stream().mapToDouble(HistoricalCandle::high).max().orElse(bseQuote.ltp());
            double bseWeek52Low = alternateOneYearHistory.stream().mapToDouble(HistoricalCandle::low).min().orElse(bseQuote.ltp());
            result.put("price_levels_nse", buildPriceLevels(primaryQuote, week52High, week52Low));
            result.put("price_levels_bse", buildPriceLevels(bseQuote, bseWeek52High, bseWeek52Low));
        } else {
            result.put("price_levels", buildPriceLevels(primaryQuote, week52High, week52Low));
        }

        result.put("trend", Map.of(
                "exchange", primaryExchange,
                "sma_50", round2(sma50),
                "sma_200", round2(sma200),
                "price_vs_50dma", percent(currentPrice - sma50, sma50),
                "price_vs_200dma", percent(currentPrice - sma200, sma200),
                "trend_50dma_direction", movingAverageSlope(oneYearHistory, 50, 20),
                "return_1m", round2(oneMonthReturn),
                "return_3m", round2(threeMonthReturn),
                "return_6m", round2(sixMonthReturn)
        ));
        result.put("volume", Map.of(
                "avg_volume_20d", round2(volume20),
                "avg_volume_60d", round2(volume60),
                "volume_trend", volumeTrend
        ));
        if (sixMonthHistory.isEmpty() || oneYearHistory.isEmpty()) {
            result.put("warnings", List.of("Historical data was incomplete, so some trend calculations may be approximate."));
        }
        return result;
    }

    Map<String, Object> marketPulse() {
        MarketSession marketSession = currentMarketSession();
        LocalDate today = LocalDate.now(clock);
        String expiryDate = nextWeeklyExpiry(today);
        CompletableFuture<QuoteSnapshot> bankNiftyFuture = submit(() -> safeGetQuote("CNXBAN", "NSE", "cash"));
        CompletableFuture<QuoteSnapshot> niftyFuture = submit(() -> dataProvider.getQuote("NIFTY", "NSE", "cash"));
        CompletableFuture<List<HistoricalCandle>> niftyHistoryFuture =
                submit(() -> dataProvider.getHistoricalCharts("NIFTY", "NSE", "cash", "day", today.minusMonths(2), today));
        CompletableFuture<List<OptionChainSnapshot>> callsFuture = submit(() -> safeGetOptionChain("NIFTY", expiryDate, "call"));
        CompletableFuture<List<OptionChainSnapshot>> putsFuture = submit(() -> safeGetOptionChain("NIFTY", expiryDate, "put"));

        QuoteSnapshot bankNifty = joinUnchecked(bankNiftyFuture);
        QuoteSnapshot nifty = joinUnchecked(niftyFuture);
        List<HistoricalCandle> niftyHistory = joinUnchecked(niftyHistoryFuture);
        List<OptionChainSnapshot> calls = joinUnchecked(callsFuture);
        List<OptionChainSnapshot> puts = joinUnchecked(putsFuture);

        double dma20 = movingAverage(niftyHistory, 20);
        double dma50 = movingAverage(niftyHistory, 50);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", ZonedDateTime.now(clock).toString());
        result.put("market_session", marketSession.toMap());
        Map<String, Object> indicesMap = new LinkedHashMap<>();
        indicesMap.put("nifty", Map.of(
                "value", round2(nifty.ltp()),
                "change", round2(nifty.ltp() - nifty.previousClose()),
                "change_pct", percent(nifty.ltp() - nifty.previousClose(), nifty.previousClose()),
                "day_high", round2(nifty.high()),
                "day_low", round2(nifty.low())
        ));
        if (bankNifty != null) {
            indicesMap.put("bank_nifty", Map.of(
                    "value", round2(bankNifty.ltp()),
                    "change", round2(bankNifty.ltp() - bankNifty.previousClose()),
                    "change_pct", percent(bankNifty.ltp() - bankNifty.previousClose(), bankNifty.previousClose())
            ));
        } else {
            indicesMap.put("bank_nifty", Map.of("unavailable", "Quote unavailable â€” verify stock code for Bank Nifty"));
        }
        result.put("indices", indicesMap);
        result.put("nifty_trend", Map.of(
                "vs_20dma", percent(nifty.ltp() - dma20, dma20),
                "vs_50dma", percent(nifty.ltp() - dma50, dma50),
                "direction_20d", movingAverageSlope(niftyHistory, 20, 10)
        ));
        if (!calls.isEmpty() || !puts.isEmpty()) {
            double totalCallOi = calls.stream().mapToDouble(OptionChainSnapshot::openInterest).sum();
            double totalPutOi = puts.stream().mapToDouble(OptionChainSnapshot::openInterest).sum();
            double pcr = totalCallOi == 0 ? 0 : totalPutOi / totalCallOi;
            String interpretation = pcr > 1.2 ? "Bullish" : pcr < 0.8 ? "Bearish" : "Neutral";
            Map<Double, Double> combinedOi = new HashMap<>();
            calls.forEach(item -> combinedOi.merge(item.strikePrice(), item.openInterest(), Double::sum));
            puts.forEach(item -> combinedOi.merge(item.strikePrice(), item.openInterest(), Double::sum));
            double highestCombinedOiStrike = combinedOi.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(0d);
            double maxPainStrike = combinedOi.keySet().stream()
                    .min(Comparator.comparingDouble(candidate ->
                            totalOptionWriterPayout(candidate, calls, puts)))
                    .orElse(0d);
            result.put("sentiment", Map.of(
                    "pcr", round2(pcr),
                    "interpretation", interpretation,
                    "total_call_oi", round2(totalCallOi),
                    "total_put_oi", round2(totalPutOi),
                    "max_pain_strike", round2(maxPainStrike),
                    "highest_combined_oi_strike", round2(highestCombinedOiStrike)
            ));
        } else {
            result.put("sentiment", Map.of("unavailable", "Option chain data not accessible (F&O segment not enabled)"));
        }
        return result;
    }

    MarketSession currentMarketSession() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        DayOfWeek day = now.getDayOfWeek();
        LocalTime time = now.toLocalTime();
        LocalTime open = LocalTime.of(9, 15);
        LocalTime close = LocalTime.of(15, 30);

        boolean weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
        boolean openNow = !weekend && !time.isBefore(open) && !time.isAfter(close);
        String status = openNow
                ? "OPEN"
                : weekend ? "CLOSED_WEEKEND"
                : time.isBefore(open) ? "CLOSED_PREOPEN"
                : "CLOSED_AFTER_HOURS";
        LocalDate lastCompletedTradingDay = openNow
                ? now.toLocalDate()
                : lastCompletedTradingDay(now.toLocalDate(), time, open);
        ZonedDateTime nextOpen = openNow ? null : nextMarketOpen(now, open, close);
        String message = openNow
                ? "Market is open. Intraday fields refer to the active NSE cash session."
                : "Market is closed. Daily change fields should be interpreted as the last completed trading session, not live intraday movement.";
        return new MarketSession(now, openNow, status, lastCompletedTradingDay, nextOpen, message);
    }

    String normalizeExchange(String exchange) {
        if (exchange == null || exchange.isBlank()) {
            return "NSE";
        }
        String upper = exchange.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("NSE", "BSE", "BOTH").contains(upper)) {
            throw new BreezeApiException("exchange must be 'NSE', 'BSE', or 'BOTH'");
        }
        return upper;
    }

    double movingAverage(List<HistoricalCandle> candles, int period) {
        if (candles.isEmpty()) {
            return 0;
        }
        List<HistoricalCandle> slice = candles.stream().skip(Math.max(0, candles.size() - period)).toList();
        return slice.stream().mapToDouble(HistoricalCandle::close).average().orElse(0);
    }

    String movingAverageSlope(List<HistoricalCandle> candles, int averagePeriod, int lookback) {
        if (candles.size() < averagePeriod + lookback) {
            return "Flat";
        }
        double recent = movingAverage(candles, averagePeriod);
        double earlier = candles.subList(0, candles.size() - lookback).stream()
                .skip(Math.max(0, candles.size() - lookback - averagePeriod))
                .mapToDouble(HistoricalCandle::close)
                .average()
                .orElse(recent);
        if (recent > earlier * 1.01) {
            return "Rising";
        }
        if (recent < earlier * 0.99) {
            return "Falling";
        }
        return "Flat";
    }

    double trailingReturn(List<HistoricalCandle> candles, int lookback) {
        if (candles.size() <= lookback) {
            return 0;
        }
        double current = candles.get(candles.size() - 1).close();
        double earlier = candles.get(candles.size() - 1 - lookback).close();
        return percent(current - earlier, earlier);
    }

    double averageVolume(List<HistoricalCandle> candles, int lookback) {
        if (candles.isEmpty()) {
            return 0;
        }
        return candles.stream()
                .skip(Math.max(0, candles.size() - lookback))
                .mapToDouble(HistoricalCandle::volume)
                .average()
                .orElse(0);
    }

    double percent(double numerator, double denominator) {
        if (denominator == 0) {
            return 0;
        }
        return round2((numerator / denominator) * 100);
    }

    double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private QuoteSnapshot safeGetQuote(String stockCode, String exchangeCode, String productType) {
        try {
            return dataProvider.getQuote(stockCode, exchangeCode, productType);
        } catch (Exception e) {
            log.warn("Quote unavailable for {}: {}", stockCode, e.getMessage());
            return null;
        }
    }

    private List<OptionChainSnapshot> safeGetOptionChain(String stockCode, String expiryDate, String right) {
        try {
            return dataProvider.getOptionChain(stockCode, expiryDate, right);
        } catch (Exception e) {
            log.warn("Option chain unavailable for {} {}: {}", stockCode, right, e.getMessage());
            return List.of();
        }
    }

    private String nextWeeklyExpiry(LocalDate today) {
        LocalDate expiry = today;
        while (expiry.getDayOfWeek() != DayOfWeek.THURSDAY) {
            expiry = expiry.plusDays(1);
        }
        return expiry.atStartOfDay(INDIA).toInstant().toString();
    }

    private double totalOptionWriterPayout(double candidateStrike,
                                           List<OptionChainSnapshot> calls,
                                           List<OptionChainSnapshot> puts) {
        double callPayout = calls.stream()
                .mapToDouble(item -> Math.max(0, candidateStrike - item.strikePrice()) * item.openInterest())
                .sum();
        double putPayout = puts.stream()
                .mapToDouble(item -> Math.max(0, item.strikePrice() - candidateStrike) * item.openInterest())
                .sum();
        return callPayout + putPayout;
    }

    private <T> CompletableFuture<T> submit(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, analysisExecutor);
    }

    private <T> T joinUnchecked(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new BreezeApiException("Concurrent analysis call failed", cause);
        }
    }

    private Object buildPositionBlock(HoldingSnapshot holding, QuoteSnapshot quote, double portfolioTotalValue) {
        if (holding == null) {
            return null;
        }
        double currentValue = holding.quantity() * quote.ltp();
        double pnl = currentValue - (holding.quantity() * holding.averagePrice());
        return Map.of(
                "quantity", round2(holding.quantity()),
                "avg_price", round2(holding.averagePrice()),
                "current_price", round2(quote.ltp()),
                "pnl_abs", round2(pnl),
                "pnl_pct", percent(pnl, holding.quantity() * holding.averagePrice()),
                "portfolio_weight_pct", percent(currentValue, portfolioTotalValue)
        );
    }

    private Map<String, Object> buildPriceLevels(QuoteSnapshot quote, double week52High, double week52Low) {
        double currentPrice = quote.ltp();
        return Map.of(
                "ltp", round2(currentPrice),
                "day_open", round2(quote.open()),
                "day_high", round2(quote.high()),
                "day_low", round2(quote.low()),
                "week_52_high", round2(week52High),
                "week_52_low", round2(week52Low),
                "distance_from_52w_high_pct", percent(currentPrice - week52High, week52High),
                "distance_from_52w_low_pct", percent(currentPrice - week52Low, week52Low)
        );
    }

    private HoldingSnapshot findHolding(List<HoldingSnapshot> holdings, String code, String isin) {
        String canonicalJoinKey = stockMetadataService.equityJoinKey(code, null);
        String normalizedIsin = isin == null || isin.isBlank() ? null : isin.trim().toUpperCase(Locale.ROOT);
        return holdings.stream()
                .filter(item -> {
                    String holdingCanonicalJoinKey = stockMetadataService.equityJoinKey(item.stockCode(), null);
                    if (holdingCanonicalJoinKey.equals(canonicalJoinKey)) {
                        return true;
                    }
                    if (normalizedIsin == null || item.isin() == null || item.isin().isBlank()) {
                        return false;
                    }
                    return item.isin().trim().toUpperCase(Locale.ROOT).equals(normalizedIsin);
                })
                .findFirst()
                .orElse(null);
    }

    private LocalDate lastCompletedTradingDay(LocalDate date, LocalTime time, LocalTime open) {
        LocalDate candidate = time.isBefore(open) ? date.minusDays(1) : date;
        while (candidate.getDayOfWeek() == DayOfWeek.SATURDAY || candidate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    private ZonedDateTime nextMarketOpen(ZonedDateTime now, LocalTime open, LocalTime close) {
        LocalDate candidateDate = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        if (candidateDate.getDayOfWeek() == DayOfWeek.SATURDAY || candidateDate.getDayOfWeek() == DayOfWeek.SUNDAY || !time.isBefore(close)) {
            candidateDate = nextTradingDay(candidateDate.plusDays(1));
        } else if (time.isBefore(open)) {
            candidateDate = nextTradingDay(candidateDate);
        }
        return candidateDate.atTime(open).atZone(INDIA);
    }

    private LocalDate nextTradingDay(LocalDate date) {
        LocalDate candidate = date;
        while (candidate.getDayOfWeek() == DayOfWeek.SATURDAY || candidate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    record MarketSession(
            ZonedDateTime timestamp,
            boolean isOpen,
            String status,
            LocalDate lastCompletedTradingDay,
            ZonedDateTime nextOpen,
            String message
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("timestamp", timestamp.toString());
            result.put("is_open", isOpen);
            result.put("status", status);
            result.put("last_completed_trading_day", lastCompletedTradingDay.toString());
            if (nextOpen != null) {
                result.put("next_open", nextOpen.toString());
            }
            result.put("message", message);
            return result;
        }
    }
}
