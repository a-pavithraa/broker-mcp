package com.broker.analysis;

import com.broker.model.AnalysisModels.*;
import com.broker.reference.StockMetadataService;
import org.springframework.lang.Nullable;

import java.util.*;
import java.util.function.Function;

class PortfolioAnalysisService {

    private final StockMetadataService stockMetadataService;

    PortfolioAnalysisService(StockMetadataService stockMetadataService) {
        this.stockMetadataService = stockMetadataService;
    }

    Map<String, Object> buildPortfolioSnapshot(List<HoldingSnapshot> holdings,
                                               List<FundsSnapshot> funds,
                                               Map<String, Object> marketSession) {
        List<PositionView> positions = enrichPositions(holdings);
        Set<String> activeSources = new LinkedHashSet<>();
        holdings.stream().map(HoldingSnapshot::broker).filter(Objects::nonNull).forEach(b -> {
            if ("composite".equals(b)) {
                activeSources.add("icici");
                activeSources.add("zerodha");
            } else {
                activeSources.add(b);
            }
        });
        funds.stream().map(FundsSnapshot::broker).filter(Objects::nonNull)
                .filter(b -> !"composite".equals(b)).forEach(activeSources::add);

        double totalInvestment = positions.stream().mapToDouble(PositionView::investment).sum();
        double currentValue = positions.stream().mapToDouble(PositionView::currentValue).sum();
        double todaysChange = positions.stream().mapToDouble(PositionView::todaysChange).sum();
        double totalPnl = currentValue - totalInvestment;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("market_session", marketSession);
        result.put("summary", Map.of(
                "total_investment", round2(totalInvestment),
                "current_value", round2(currentValue),
                "total_pnl", round2(totalPnl),
                "total_pnl_pct", percent(totalPnl, totalInvestment),
                "todays_change", round2(todaysChange),
                "todays_change_pct", percent(todaysChange, currentValue - todaysChange),
                "cash_available", round2(totalUnallocatedBalance(funds)),
                "holdings_count", positions.size()
        ));
        result.put("todays_top_gainers", topMovers(positions, Comparator.comparingDouble(PositionView::todaysChange).reversed()));
        result.put("todays_top_losers", topMovers(positions, Comparator.comparingDouble(PositionView::todaysChange)));
        result.put("top_holdings_by_value", positions.stream()
                .sorted(Comparator.comparingDouble(PositionView::currentValue).reversed())
                .limit(5)
                .map(position -> Map.of(
                        "name", position.name(),
                        "code", position.code(),
                        "value", round2(position.currentValue()),
                        "weight_pct", percent(position.currentValue(), currentValue)
                ))
                .toList());
        result.put("biggest_winners_overall", positions.stream()
                .sorted(Comparator.comparingDouble(PositionView::pnl).reversed())
                .limit(5)
                .map(position -> overallPnlEntry(position, currentValue))
                .toList());
        result.put("biggest_losers_overall", positions.stream()
                .sorted(Comparator.comparingDouble(PositionView::pnl))
                .limit(5)
                .map(position -> overallPnlEntry(position, currentValue))
                .toList());
        result.put("data_sources", activeSources.isEmpty() ? List.of("unknown") : List.copyOf(activeSources));
        if (activeSources.size() == 1) {
            String source = activeSources.iterator().next();
            result.put("warning", "Data is from " + source + " only. " +
                    "If you expected data from another broker, run breeze_session_status to check session state.");
        }
        return result;
    }

    Map<String, Object> buildPortfolioHealth(List<HoldingSnapshot> holdings,
                                             List<FundsSnapshot> funds,
                                             List<HistoricalCandle> niftyHistory,
                                             @Nullable List<GttOrderSnapshot> gttOrders,
                                             Map<String, Object> marketSession,
                                             boolean marketOpen) {
        List<PositionView> positions = enrichPositions(holdings);
        double currentValue = positions.stream().mapToDouble(PositionView::currentValue).sum();
        double investment = positions.stream().mapToDouble(PositionView::investment).sum();
        List<PositionView> sortedByWeight = positions.stream()
                .sorted(Comparator.comparingDouble(PositionView::currentValue).reversed())
                .toList();
        double topFiveWeight = sortedByWeight.stream().limit(5).mapToDouble(p -> percent(p.currentValue(), currentValue)).sum();
        double hhi = positions.stream()
                .mapToDouble(p -> Math.pow(p.currentValue() / safeDenominator(currentValue), 2))
                .sum();
        double equalWeightHhi = positions.isEmpty() ? 0 : round4(1.0 / positions.size());
        double effectiveStockCount = hhi == 0 ? 0 : round2(1.0 / hhi);
        double concentrationRatio = equalWeightHhi == 0 ? 0 : round2(hhi / equalWeightHhi);

        Map<String, Double> sectorWeights = aggregateByPositionLabel(positions, currentValue, PositionView::sector);
        Map<String, Double> groupWeights = aggregateByPositionLabel(positions, currentValue, PositionView::group);
        DataCoverage coverage = computeDataCoverage(positions, currentValue);

        int stocksInProfit = (int) positions.stream().filter(p -> p.pnl() >= 0).count();
        int stocksInLoss = positions.size() - stocksInProfit;
        double portfolioReturn = percent(currentValue - investment, investment);
        double niftyReturn = benchmarkReturn(niftyHistory);

        HeuristicEvaluation heuristicEvaluation = evaluateHeuristics(positions, sortedByWeight, currentValue,
                groupWeights, sectorWeights, gttOrders);

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("market_session", marketSession);
        facts.put("portfolio_summary", Map.of(
                "total_stocks", positions.size(),
                "total_investment", round2(investment),
                "current_value", round2(currentValue),
                "total_return_since_purchase_pct", round2(portfolioReturn),
                "stocks_in_profit", stocksInProfit,
                "stocks_in_loss", stocksInLoss,
                "cash_balance", round2(totalUnallocatedBalance(funds))
        ));
        facts.put("concentration", Map.of(
                "top_5_weight_pct", round2(topFiveWeight),
                "top_5_stocks", sortedByWeight.stream().limit(5).map(PositionView::code).toList(),
                "hhi_index", round4(hhi),
                "equal_weight_reference_hhi", equalWeightHhi,
                "concentration_ratio_to_equal_weight", concentrationRatio,
                "effective_stock_count", effectiveStockCount
        ));
        facts.put("group_exposure", buildGroupedExposure(positions, groupWeights, "group", PositionView::group));
        facts.put("sector_allocation", buildGroupedExposure(positions, sectorWeights, "sector", PositionView::sector));
        facts.put("market_context", Map.of(
                "nifty_trailing_1yr_return_pct", round2(niftyReturn),
                "window", "trailing_1yr",
                "note", "Market context only. Covers a different time period than total_return_since_purchase_pct and must not be used to compute outperformance."
        ));

        Map<String, Object> heuristics = new LinkedHashMap<>();
        heuristics.put("heuristic_score", Map.of(
                "value", heuristicEvaluation.score().score(),
                "label", heuristicEvaluation.score().label(),
                "max_score", 10,
                "basis", "product policy thresholds"
        ));
        heuristics.put("score_methodology", Map.of(
                "severity_weights", Map.of(
                        "high", 3,
                        "medium", 2,
                        "low", 1
                ),
                "note", "This score is heuristic and based on product policy thresholds, not an objective measure of portfolio quality."
        ));
        heuristics.put("flags", heuristicEvaluation.flags().stream().map(HeuristicFlag::toMap).toList());
        heuristics.put("positions_to_review", heuristicEvaluation.positionsToReview());
        heuristics.put("important_sector_gaps", heuristicEvaluation.importantSectorGaps());

        List<String> scopeLimitations = new ArrayList<>();
        scopeLimitations.add("Portfolio return is based on current holdings versus invested cost basis. NIFTY is shown only as a 1-year benchmark fact and is not directly comparable to the portfolio return.");
        scopeLimitations.add("Asset allocation across equity, debt, gold, and holdings outside this brokerage account cannot be evaluated from this account alone.");
        if (!marketOpen) {
            scopeLimitations.add("Market is currently closed. Any daily change figures available elsewhere should be treated as the last completed trading session, not live intraday movement.");
        }
        if (gttOrders == null) {
            scopeLimitations.add("Protective GTT status could not be evaluated because GTT orders were unavailable.");
        }

        result.put("facts", facts);
        result.put("heuristics", heuristics);
        result.put("scope_limitations", scopeLimitations);
        result.put("data_coverage", coverage.toMap());
        return result;
    }

    List<PositionView> enrichPositions(List<HoldingSnapshot> holdings) {
        return holdings.stream()
                .map(holding -> {
                    StockMetadata metadata = stockMetadataService.getMetadata(holding.stockCode());
                    double ltp = holding.currentMarketPrice();
                    double previousClose = ltp / (1 + holding.changePercentage() / 100.0);
                    double investment = holding.quantity() * holding.averagePrice();
                    double currentValue = holding.quantity() * ltp;
                    double pnl = currentValue - investment;
                    double todaysChange = holding.quantity() * (ltp - previousClose);
                    return new PositionView(
                            holding.stockCode(),
                            preferredName(holding, metadata),
                            normalizeLabel(metadata == null ? null : metadata.sector()),
                            normalizeLabel(metadata == null ? null : metadata.group()),
                            holding.quantity(),
                            holding.averagePrice(),
                            ltp,
                            investment,
                            currentValue,
                            pnl,
                            todaysChange
                    );
                })
                .toList();
    }

    private List<Map<String, Object>> buildGroupedExposure(List<PositionView> positions,
                                                           Map<String, Double> weights,
                                                           String labelName,
                                                           Function<PositionView, String> extractor) {
        return weights.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put(labelName, entry.getKey());
                    item.put("stocks", positions.stream()
                            .filter(position -> Objects.equals(entry.getKey(), extractor.apply(position)))
                            .map(PositionView::code)
                            .toList());
                    item.put("count", positions.stream()
                            .filter(position -> Objects.equals(entry.getKey(), extractor.apply(position)))
                            .count());
                    item.put("weight_pct", round2(entry.getValue()));
                    return item;
                })
                .toList();
    }

    List<Map<String, Object>> topMovers(List<PositionView> positions, Comparator<PositionView> comparator) {
        return positions.stream()
                .sorted(comparator)
                .limit(5)
                .map(position -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("code", position.code());
                    item.put("name", position.name());
                    item.put("change_today_pct", percent(position.todaysChange(), position.currentValue() - position.todaysChange()));
                    item.put("change_today_abs", round2(position.todaysChange()));
                    return item;
                })
                .toList();
    }

    Map<String, Object> overallPnlEntry(PositionView position, double totalCurrentValue) {
        return Map.of(
                "code", position.code(),
                "name", position.name(),
                "pnl_pct", percent(position.pnl(), position.investment()),
                "pnl_abs", round2(position.pnl()),
                "portfolio_weight_pct", percent(position.currentValue(), totalCurrentValue)
        );
    }

    String normalizeLabel(String value) {
        return value == null || value.isBlank() ? "Other" : value;
    }

    private String preferredName(HoldingSnapshot holding, StockMetadata metadata) {
        if (metadata != null) {
            return metadata.name();
        }
        return (holding.stockName() == null || holding.stockName().isBlank()) ? holding.stockCode() : holding.stockName();
    }

    private double totalUnallocatedBalance(List<FundsSnapshot> funds) {
        if (funds == null || funds.isEmpty()) {
            return 0;
        }
        return funds.stream().mapToDouble(FundsSnapshot::unallocatedBalance).sum();
    }

    private double percent(double numerator, double denominator) {
        if (denominator == 0) {
            return 0;
        }
        return round2((numerator / denominator) * 100);
    }

    private double safeDenominator(double value) {
        return value == 0 ? 1 : value;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private double benchmarkReturn(List<HistoricalCandle> niftyHistory) {
        if (niftyHistory.size() < 2) {
            return 0;
        }
        double start = niftyHistory.get(0).close();
        double end = niftyHistory.get(niftyHistory.size() - 1).close();
        return percent(end - start, start);
    }

    private Map<String, Double> aggregateByPositionLabel(List<PositionView> positions, double totalValue,
                                                         Function<PositionView, String> extractor) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (PositionView position : positions) {
            String key = normalizeLabel(extractor.apply(position));
            result.merge(key, percent(position.currentValue(), totalValue), Double::sum);
        }
        return result;
    }

    private HeuristicEvaluation evaluateHeuristics(List<PositionView> positions,
                                                   List<PositionView> sortedByWeight,
                                                   double currentValue,
                                                   Map<String, Double> groupWeights,
                                                   Map<String, Double> sectorWeights,
                                                   @Nullable List<GttOrderSnapshot> gttOrders) {
        List<HeuristicFlag> flags = new ArrayList<>();
        flags.add(evaluateSingleStockFlag(sortedByWeight, currentValue));
        flags.add(evaluateSingleGroupFlag(groupWeights));
        flags.add(evaluateLossValueFlag(positions, currentValue));
        List<Map<String, Object>> positionsToReview = buildPositionsToReview(positions, currentValue);
        flags.add(evaluateMaterialDrawdownFlag(positionsToReview));
        if (gttOrders != null) {
            flags.add(evaluateProtectiveGttFlag(gttOrders));
        }
        List<Map<String, Object>> importantSectorGaps = importantSectorGaps(sectorWeights);
        return new HeuristicEvaluation(flags, positionsToReview, importantSectorGaps, computeHealthScore(flags));
    }

    private HeuristicFlag evaluateSingleStockFlag(List<PositionView> sortedByWeight, double currentValue) {
        PositionView largestPosition = sortedByWeight.isEmpty() ? null : sortedByWeight.getFirst();
        double largestPositionWeight = largestPosition == null ? 0 : percent(largestPosition.currentValue(), currentValue);
        return new HeuristicFlag(
                "single_stock_over_20pct",
                largestPositionWeight > 20,
                largestPositionWeight > 20
                        ? largestPosition.code() + " is " + round2(largestPositionWeight) + "% of portfolio value, above the 20.0% product-policy threshold."
                        : "No single stock exceeds the 20.0% product-policy threshold.",
                "high",
                3,
                Map.of("kind", "product_policy", "metric", "stock_weight_pct", "operator", ">", "value", 20.0),
                largestPosition == null ? Map.of("value_pct", 0.0) : Map.of("stock", largestPosition.code(), "value_pct", round2(largestPositionWeight))
        );
    }

    private HeuristicFlag evaluateSingleGroupFlag(Map<String, Double> groupWeights) {
        Map.Entry<String, Double> largestGroup = groupWeights.entrySet().stream()
                .filter(entry -> !"Other".equals(entry.getKey()))
                .max(Map.Entry.comparingByValue())
                .orElse(null);
        double largestGroupWeight = largestGroup == null ? 0 : largestGroup.getValue();
        return new HeuristicFlag(
                "single_group_over_25pct",
                largestGroupWeight > 25,
                largestGroupWeight > 25
                        ? largestGroup.getKey() + " group is " + round2(largestGroupWeight)
                        + "% of portfolio value, above the 25.0% product-policy threshold."
                        : "No single group exceeds the 25.0% product-policy threshold.",
                "high",
                3,
                Map.of("kind", "product_policy", "metric", "group_weight_pct", "operator", ">", "value", 25.0),
                largestGroup == null ? Map.of("value_pct", 0.0) : Map.of("group", largestGroup.getKey(), "value_pct", round2(largestGroupWeight))
        );
    }

    private HeuristicFlag evaluateLossValueFlag(List<PositionView> positions, double currentValue) {
        double valueInLoss = positions.stream()
                .filter(position -> position.pnl() < 0)
                .mapToDouble(PositionView::currentValue)
                .sum();
        double valueInLossPct = percent(valueInLoss, currentValue);
        return new HeuristicFlag(
                "loss_value_over_50pct",
                valueInLossPct > 50,
                valueInLossPct > 50
                        ? round2(valueInLossPct) + "% of current portfolio value is in positions that are in loss, above the 50.0% review threshold."
                        : round2(valueInLossPct) + "% of current portfolio value is in positions that are in loss, currently below the 50.0% review threshold.",
                "medium",
                2,
                Map.of("kind", "product_policy", "metric", "loss_position_value_pct", "operator", ">", "value", 50.0),
                Map.of("value_pct", round2(valueInLossPct))
        );
    }

    private List<Map<String, Object>> buildPositionsToReview(List<PositionView> positions, double currentValue) {
        return positions.stream()
                .map(position -> buildReviewCandidate(position, currentValue))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble((Map<String, Object> item) -> number(item.get("portfolio_weight_pct"))).reversed())
                .toList();
    }

    private HeuristicFlag evaluateMaterialDrawdownFlag(List<Map<String, Object>> positionsToReview) {
        boolean materialDrawdownTriggered = positionsToReview.stream()
                .anyMatch(item -> Boolean.TRUE.equals(item.get("counts_in_score")));
        return new HeuristicFlag(
                "material_drawdown_review",
                materialDrawdownTriggered,
                materialDrawdownTriggered
                        ? "At least one position is down 30.0% or more and is above the 2.0% portfolio-weight review threshold."
                        : "No position breaches both the 30.0% drawdown and 2.0% portfolio-weight review thresholds.",
                "medium",
                2,
                Map.of(
                        "kind", "product_policy",
                        "drawdown_pct", -30.0,
                        "minimum_portfolio_weight_pct", 2.0
                ),
                Map.of("review_count", positionsToReview.size())
        );
    }

    private HeuristicFlag evaluateProtectiveGttFlag(List<GttOrderSnapshot> gttOrders) {
        return new HeuristicFlag(
                "no_protective_gtt",
                gttOrders.isEmpty(),
                gttOrders.isEmpty()
                        ? "No active protective GTT orders were found."
                        : "Protective GTT orders already exist.",
                "low",
                1,
                Map.of("kind", "product_policy", "metric", "active_gtt_order_count", "operator", "=", "value", 0),
                Map.of("active_order_count", gttOrders.size())
        );
    }

    private List<Map<String, Object>> importantSectorGaps(Map<String, Double> sectorWeights) {
        List<String> importantSectors = List.of(
                "Financial Services",
                "Information Technology",
                "Healthcare",
                "Power",
                "Fast Moving Consumer Goods"
        );
        return importantSectors.stream()
                .map(sector -> {
                    double weight = round2(sectorWeights.getOrDefault(sector, 0.0));
                    if (weight >= 3.0) {
                        return null;
                    }
                    Map<String, Object> gap = new LinkedHashMap<>();
                    gap.put("sector", sector);
                    gap.put("weight_pct", weight);
                    gap.put("meaningful_weight_threshold_pct", 3.0);
                    gap.put("status", weight == 0 ? "missing" : "below_meaningful_threshold");
                    return gap;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private HealthScore computeHealthScore(List<HeuristicFlag> flags) {
        int totalDeduction = flags.stream()
                .filter(HeuristicFlag::triggered)
                .mapToInt(HeuristicFlag::scoreWeight)
                .sum();
        int score = Math.max(1, 10 - totalDeduction);
        String label = score >= 9 ? "Excellent" : score >= 7 ? "Good" : score >= 4 ? "Needs Attention" : "At Risk";
        return new HealthScore(score, label);
    }

    private Map<String, Object> buildReviewCandidate(PositionView position, double currentValue) {
        double pnlPct = percent(position.pnl(), position.investment());
        if (pnlPct > -30) {
            return null;
        }
        double weightPct = percent(position.currentValue(), currentValue);
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("code", position.code());
        candidate.put("name", position.name());
        candidate.put("pnl_pct", round2(pnlPct));
        candidate.put("portfolio_weight_pct", round2(weightPct));
        candidate.put("counts_in_score", weightPct >= 2.0);
        candidate.put("review_reason", weightPct >= 2.0
                ? "Down 30.0% or more and above the 2.0% portfolio-weight review threshold."
                : "Down 30.0% or more, but below the 2.0% portfolio-weight scoring threshold.");
        return candidate;
    }

    private DataCoverage computeDataCoverage(List<PositionView> positions, double totalValue) {
        double resolvedSectorValue = positions.stream()
                .filter(position -> !"Other".equals(position.sector()))
                .mapToDouble(PositionView::currentValue)
                .sum();
        double resolvedGroupValue = positions.stream()
                .filter(position -> !"Other".equals(position.group()))
                .mapToDouble(PositionView::currentValue)
                .sum();
        List<String> stocksWithoutSector = positions.stream()
                .filter(position -> "Other".equals(position.sector()))
                .map(PositionView::code)
                .toList();
        List<String> stocksWithoutGroup = positions.stream()
                .filter(position -> "Other".equals(position.group()))
                .map(PositionView::code)
                .toList();
        List<String> unresolvedStocks = positions.stream()
                .filter(position -> "Other".equals(position.sector()) || "Other".equals(position.group()))
                .map(PositionView::code)
                .distinct()
                .toList();
        return new DataCoverage(
                round2(percent(resolvedSectorValue, totalValue)),
                round2(percent(resolvedGroupValue, totalValue)),
                stocksWithoutSector,
                stocksWithoutGroup,
                unresolvedStocks
        );
    }

    private double number(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).replace(",", ""));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private record HealthScore(int score, String label) {
    }

    private record HeuristicEvaluation(
            List<HeuristicFlag> flags,
            List<Map<String, Object>> positionsToReview,
            List<Map<String, Object>> importantSectorGaps,
            HealthScore score
    ) {
    }

    private record HeuristicFlag(
            String name,
            boolean triggered,
            String detail,
            String severity,
            int scoreWeight,
            Map<String, Object> threshold,
            Map<String, Object> observed
    ) {
        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            result.put("triggered", triggered);
            result.put("detail", detail);
            result.put("severity", severity);
            result.put("score_weight", scoreWeight);
            result.put("threshold", threshold);
            result.put("observed", observed);
            return result;
        }
    }

    private record DataCoverage(
            double pctPortfolioValueWithSector,
            double pctPortfolioValueWithGroup,
            List<String> stocksWithoutSector,
            List<String> stocksWithoutGroup,
            List<String> unresolvedStocks
    ) {
        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pct_portfolio_value_with_sector", pctPortfolioValueWithSector);
            result.put("pct_portfolio_value_with_group", pctPortfolioValueWithGroup);
            result.put("stocks_without_sector", stocksWithoutSector);
            result.put("stocks_without_group", stocksWithoutGroup);
            result.put("unresolved_stocks", unresolvedStocks);
            return result;
        }
    }

}

record PositionView(
        String code,
        String name,
        String sector,
        String group,
        double quantity,
        double averagePrice,
        double currentPrice,
        double investment,
        double currentValue,
        double pnl,
        double todaysChange
) {
}
