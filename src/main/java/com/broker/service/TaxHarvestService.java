package com.broker.service;

import com.broker.model.AnalysisModels.HoldingSnapshot;
import com.broker.model.AnalysisModels.TradeSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

class TaxHarvestService {

    private static final Logger log = LoggerFactory.getLogger(TaxHarvestService.class);
    private static final LocalDate GRANDFATHERING_CUTOFF = LocalDate.of(2018, 2, 1);
    private static final double LTCG_TAX_RATE = 0.125 * 1.04;
    private static final double STCG_TAX_RATE = 0.20 * 1.04;

    private final StockMetadataService stockMetadataService;
    private final CorporateActionService corporateActionService;
    private final ZerodhaTradebookService zerodhaTradebookService;
    private final int tradeHistoryYears;

    TaxHarvestService(
            StockMetadataService stockMetadataService,
            CorporateActionService corporateActionService,
            @Nullable ZerodhaTradebookService zerodhaTradebookService,
            int tradeHistoryYears
    ) {
        this.stockMetadataService = stockMetadataService;
        this.corporateActionService = corporateActionService;
        this.zerodhaTradebookService = zerodhaTradebookService;
        this.tradeHistoryYears = tradeHistoryYears;
    }

    Map<String, Object> buildTaxHarvestReport(List<HoldingSnapshot> holdings, List<TradeSnapshot> apiTrades, LocalDate today) {
        FinancialYearRange currentFy = currentFinancialYear(today);
        TaxTradeInputs taxTradeInputs = buildTaxTradeInputs(apiTrades, holdings, currentFy.start(), today);
        List<TradeSnapshot> trades = taxTradeInputs.trades();

        FifoProcessingResult fifo = processTimeline(trades, holdings, currentFy, today);
        Map<String, List<BuyLot>> lotsByStock = fifo.lotsByStock();
        RealizedGains realized = fifo.realizedGains();
        UnrealizedAnalysis unrealized = analyzeUnrealizedLots(lotsByStock, holdings, today);

        double totalLtcg = realized.ltcg() + unrealized.ltcg();
        double totalStcg = realized.stcg() + unrealized.stcg();
        double ltcgTaxable = Math.max(0, totalLtcg - 125000);
        double stcgTaxable = Math.max(0, totalStcg);
        double estimatedLtcgTax = round2(ltcgTaxable * LTCG_TAX_RATE);
        double estimatedStcgTax = round2(stcgTaxable * STCG_TAX_RATE);
        double sameClassLtcgSavings = Math.min(unrealized.harvestableLtcgLoss(), ltcgTaxable) * LTCG_TAX_RATE;
        double sameClassStcgSavings = Math.min(unrealized.harvestableStcgLoss(), stcgTaxable) * STCG_TAX_RATE;
        double conservativeTaxSaved = sameClassLtcgSavings + sameClassStcgSavings;
        double remainingStcgLoss = Math.max(0, unrealized.harvestableStcgLoss() - Math.min(unrealized.harvestableStcgLoss(), stcgTaxable));
        double remainingLtcgTaxable = Math.max(0, ltcgTaxable - Math.min(unrealized.harvestableLtcgLoss(), ltcgTaxable));
        double additionalCrossClassSavings = Math.min(remainingStcgLoss, remainingLtcgTaxable) * LTCG_TAX_RATE;
        double maximumTaxSaved = conservativeTaxSaved + additionalCrossClassSavings;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", taxTradeInputs.coverage().status());
        result.put("brokers_fully_covered", taxTradeInputs.coverage().brokersFullyCovered());
        if (!taxTradeInputs.coverage().brokersPartial().isEmpty()) {
            result.put("brokers_partial", taxTradeInputs.coverage().brokersPartial());
        }
        if (taxTradeInputs.coverage().message() != null) {
            result.put("message", taxTradeInputs.coverage().message());
        }
        if (taxTradeInputs.coverage().recommendation() != null) {
            result.put("recommendation", taxTradeInputs.coverage().recommendation());
        }
        result.put("financial_year", currentFy.label());
        result.put("data_window", Map.of(
                "from", currentFy.start().toString(),
                "to", (today.isBefore(currentFy.end()) ? today : currentFy.end()).toString(),
                "note", today.isBefore(currentFy.end()) ? "FY in progress — realized gains shown up to today" : "Full FY"
        ));
        result.put("realized_gains_this_fy", Map.of(
                "sells", realized.details(),
                "realized_ltcg", round2(realized.ltcg()),
                "realized_stcg", round2(realized.stcg())
        ));
        result.put("unrealized_positions", Map.of(
                "ltcg_holdings", unrealized.ltcgHoldings(),
                "stcg_holdings", unrealized.stcgHoldings(),
                "unrealized_ltcg", round2(unrealized.ltcg()),
                "unrealized_stcg", round2(unrealized.stcg())
        ));
        result.put("combined_estimate", Map.of(
                "total_ltcg", round2(totalLtcg),
                "total_stcg", round2(totalStcg),
                "ltcg_exempt_upto", 125000,
                "ltcg_taxable", round2(ltcgTaxable),
                "estimated_ltcg_tax", estimatedLtcgTax,
                "estimated_stcg_tax", estimatedStcgTax,
                "tax_note", "Rates: LTCG 12.5% + 4% cess = 13%; STCG 20% + 4% cess = 20.8% (§111A/112A, Finance Act 2024)"
        ));
        Set<String> grandfatheringStocks = detectGrandfatheringCandidates(realized.details(), unrealized.ltcgHoldings());
        if (!grandfatheringStocks.isEmpty()) {
            result.put("grandfathering_notice", Map.of(
                    "stocks", new ArrayList<>(grandfatheringStocks),
                    "note", "These holdings include lots purchased before 31-Jan-2018. Under §112A, the cost of acquisition for LTCG is the higher of actual purchase price and FMV as on 31-Jan-2018 (capped at sale price). The LTCG figures above use actual purchase price — they may be overstated. Verify FMV on 31-Jan-2018 before filing."
            ));
        }
        result.put("harvest_candidates", unrealized.harvestCandidates());
        result.put("total_harvestable_loss", round2(unrealized.totalHarvestableLoss()));
        result.put("potential_tax_saved", Map.of(
                "conservative_estimate", round2(conservativeTaxSaved),
                "maximum_estimate", round2(maximumTaxSaved),
                "harvestable_ltcg_loss", round2(unrealized.harvestableLtcgLoss()),
                "harvestable_stcg_loss", round2(unrealized.harvestableStcgLoss()),
                "taxable_ltcg_available", round2(ltcgTaxable),
                "taxable_stcg_available", round2(stcgTaxable),
                "note", "Estimates are capped by current taxable gains. Conservative estimate uses same-class offsets only; maximum estimate also applies remaining STCG losses against taxable LTCG."
        ));
        result.put("estimated_from_holdings_avg", unrealized.unmatchedStocks());
        if (!unrealized.needsCorporateActionReview().isEmpty()) {
            result.put("needs_corporate_action_review", unrealized.needsCorporateActionReview());
            result.put("suspected_corporate_action_reasons", unrealized.corporateActionReviewReasons());
        }
        result.put("data_sources", taxTradeInputs.coverage().dataSources());
        return result;
    }

    private FinancialYearRange currentFinancialYear(LocalDate today) {
        int startYear = today.getMonthValue() >= Month.APRIL.getValue() ? today.getYear() : today.getYear() - 1;
        LocalDate fyEnd = LocalDate.of(startYear + 1, Month.MARCH, 31);
        return new FinancialYearRange(LocalDate.of(startYear, Month.APRIL, 1), fyEnd, startYear + "-" + String.valueOf(startYear + 1).substring(2));
    }

    private FifoProcessingResult processTimeline(
            List<TradeSnapshot> trades,
            List<HoldingSnapshot> holdings,
            FinancialYearRange fy,
            LocalDate today
    ) {
        Map<String, List<BuyLot>> lotsByStock = new LinkedHashMap<>();
        double ltcg = 0;
        double stcg = 0;
        List<Map<String, Object>> details = new ArrayList<>();

        for (TimelineEvent event : buildTimelineEvents(trades, holdings, today)) {
            if (event.corporateAction() != null) {
                applyCorporateAction(lotsByStock, event.stockCode(), event.corporateAction());
                continue;
            }

            TradeSnapshot trade = event.trade();
            if (trade == null || trade.tradeDate() == null) {
                continue;
            }

            String stockKey = trade.stockCode().toUpperCase(Locale.ROOT);
            if ("buy".equalsIgnoreCase(trade.action())) {
                lotsByStock.computeIfAbsent(stockKey, ignored -> new ArrayList<>())
                        .add(new BuyLot(trade.tradeDate(), trade.price(), trade.quantity()));
                continue;
            }
            if (!"sell".equalsIgnoreCase(trade.action())) {
                continue;
            }

            List<BuyLot> lots = lotsByStock.getOrDefault(stockKey, List.of());
            double remainingToSell = trade.quantity();
            boolean inCurrentFy = !trade.tradeDate().isBefore(fy.start()) && !trade.tradeDate().isAfter(fy.end());

            for (BuyLot lot : lots) {
                if (remainingToSell <= 0 || lot.remaining <= 0) continue;
                if (lot.date.isAfter(trade.tradeDate())) continue;
                double matched = Math.min(remainingToSell, lot.remaining);
                lot.remaining -= matched;
                remainingToSell -= matched;

                if (inCurrentFy) {
                    String classification = trade.tradeDate().isAfter(lot.date.plusMonths(12)) ? "LTCG" : "STCG";
                    double gain = (trade.price() - lot.price) * matched;
                    if ("LTCG".equals(classification)) {
                        ltcg += gain;
                    } else {
                        stcg += gain;
                    }
                    details.add(Map.of(
                            "stock", trade.stockCode(),
                            "sell_date", trade.tradeDate().toString(),
                            "sell_price", round2(trade.price()),
                            "buy_date", lot.date.toString(),
                            "buy_price", round2(lot.price),
                            "quantity", round2(matched),
                            "gain_or_loss", round2(gain),
                            "classification", classification
                    ));
                }
            }

            if (remainingToSell > 0 && inCurrentFy) {
                details.add(Map.of(
                        "stock", trade.stockCode(),
                        "sell_date", trade.tradeDate().toString(),
                        "sell_price", round2(trade.price()),
                        "quantity", round2(remainingToSell),
                        "gain_or_loss", 0.0,
                        "classification", "UNKNOWN",
                        "note", "Buy lot not found in trade history"
                ));
            }
        }

        lotsByStock.values().forEach(lots -> lots.sort(Comparator.comparing(lot -> lot.date)));
        return new FifoProcessingResult(lotsByStock, new RealizedGains(ltcg, stcg, details));
    }

    private List<TimelineEvent> buildTimelineEvents(
            List<TradeSnapshot> trades,
            List<HoldingSnapshot> holdings,
            LocalDate today
    ) {
        List<TimelineEvent> timeline = trades.stream()
                .filter(trade -> trade.tradeDate() != null)
                .map(trade -> new TimelineEvent(trade.tradeDate(), 1, trade.stockCode().toUpperCase(Locale.ROOT), trade, null))
                .collect(Collectors.toCollection(ArrayList::new));
        Set<String> trackedStocks = new java.util.LinkedHashSet<>();
        trades.stream()
                .map(TradeSnapshot::stockCode)
                .filter(Objects::nonNull)
                .map(code -> code.toUpperCase(Locale.ROOT))
                .forEach(trackedStocks::add);
        holdings.stream()
                .map(HoldingSnapshot::stockCode)
                .filter(Objects::nonNull)
                .map(stockMetadataService::resolveNseToIcici)
                .map(code -> code.toUpperCase(Locale.ROOT))
                .forEach(trackedStocks::add);
        for (String stockCode : trackedStocks) {
            corporateActionService.quantityAdjustmentActionsFor(stockCode, today).forEach(action ->
                    timeline.add(new TimelineEvent(action.exDate(), 0, stockCode, null, action)));
        }
        timeline.sort(Comparator.comparing(TimelineEvent::date)
                .thenComparingInt(TimelineEvent::sortOrder)
                .thenComparing(TimelineEvent::stockCode));
        return timeline;
    }

    private void applyCorporateAction(
            Map<String, List<BuyLot>> lotsByStock,
            String stockCode,
            CorporateActionService.CorporateAction action
    ) {
        List<BuyLot> lots = lotsByStock.get(stockCode);
        if (lots == null || lots.isEmpty()) {
            return;
        }
        for (BuyLot lot : lots) {
            if (lot.remaining <= 0) {
                continue;
            }
            lot.remaining *= action.quantityMultiplier();
            lot.price /= action.quantityMultiplier();
        }
        log.info("Applied corporate action to open tax lots: stock={} exDate={} type={} quantityMultiplier={}",
                stockCode, action.exDate(), action.type(), action.quantityMultiplier());
    }

    private UnrealizedAnalysis analyzeUnrealizedLots(Map<String, List<BuyLot>> lotsByStock,
                                                     List<HoldingSnapshot> holdings,
                                                     LocalDate today) {
        double ltcgTotal = 0;
        double stcgTotal = 0;
        double harvestableLoss = 0;
        double harvestableLtcgLoss = 0;
        double harvestableStcgLoss = 0;
        List<Map<String, Object>> ltcgHoldings = new ArrayList<>();
        List<Map<String, Object>> stcgHoldings = new ArrayList<>();
        List<Map<String, Object>> harvestCandidates = new ArrayList<>();
        List<Map<String, Object>> unmatchedStocks = new ArrayList<>();
        List<String> needsCorporateActionReview = new ArrayList<>();
        List<Map<String, Object>> corporateActionReviewReasons = new ArrayList<>();
        log.debug("lotsByStock={}", lotsByStock);
        lotsByStock.forEach((key, value) -> {
            log.debug("lotKey={}", key);
            value.forEach(v -> {
                log.debug("lotDate={} remaining={}", v.date, v.remaining);
            });
        });

        for (HoldingSnapshot holding : holdings) {
            String holdingKey = stockMetadataService.resolveNseToIcici(holding.stockCode()).toUpperCase(Locale.ROOT);
            List<BuyLot> lots = lotsByStock.getOrDefault(holdingKey, List.of());
            List<BuyLot> remaining = lots.stream().filter(l -> l.remaining > 0).toList();
            log.debug("lots={}", lots);
            log.debug("remainingLots={}", remaining);

            double matchedQty = remaining.stream().mapToDouble(l -> l.remaining).sum();
            double unmatchedQty = holding.quantity() - matchedQty;

            for (BuyLot lot : remaining) {
                long holdingMonths = ChronoUnit.MONTHS.between(lot.date, today);
                log.debug("stock={} tradeDate={} holdingMonths={}", holding.stockCode(), lot.date, holdingMonths);
                String classification = today.isAfter(lot.date.plusMonths(12)) ? "LTCG" : "STCG";
                double gainOrLoss = (holding.currentMarketPrice() - lot.price) * lot.remaining;

                Map<String, Object> entry = Map.of(
                        "stock", holding.stockCode(),
                        "buy_date", lot.date.toString(),
                        "buy_price", round2(lot.price),
                        "quantity", round2(lot.remaining),
                        "holding_months", holdingMonths,
                        "gain_or_loss", round2(gainOrLoss),
                        "classification", classification
                );
                log.debug("unrealizedEntry={}", entry);

                if ("LTCG".equals(classification)) {
                    ltcgHoldings.add(entry);
                    ltcgTotal += gainOrLoss;
                } else {
                    stcgHoldings.add(entry);
                    stcgTotal += gainOrLoss;
                }

                if (gainOrLoss < 0) {
                    harvestCandidates.add(buildHarvestCandidate(
                            holding,
                            lot.date,
                            lot.price,
                            lot.remaining,
                            classification,
                            holdingMonths,
                            gainOrLoss
                    ));
                    harvestableLoss += Math.abs(gainOrLoss);
                    if ("LTCG".equals(classification)) {
                        harvestableLtcgLoss += Math.abs(gainOrLoss);
                    } else {
                        harvestableStcgLoss += Math.abs(gainOrLoss);
                    }
                }
            }

            if (unmatchedQty > 0) {
                double totalCost = holding.averagePrice() * holding.quantity();
                double matchedCost = remaining.stream().mapToDouble(l -> l.price * l.remaining).sum();
                // Cap matchedCost at totalCost: cross-broker lots sharing the same stock key can
                // inflate matchedCost beyond what this holding paid, producing a near-zero or
                // negative unmatchedCost and a nonsense estimatedBuyPrice (e.g. ₹0.54).
                double unmatchedCost = Math.max(0, totalCost - matchedCost);
                double estimatedBuyPrice = round2(unmatchedCost / unmatchedQty);
                double gainOrLoss = (holding.currentMarketPrice() * unmatchedQty) - unmatchedCost;
                List<CorporateActionService.CorporateAction> acquisitionHints =
                        corporateActionService.acquisitionActionsFor(holdingKey, today);

                if (acquisitionHints.size() == 1) {
                    CorporateActionService.CorporateAction acquisition = acquisitionHints.getFirst();
                    long holdingMonths = ChronoUnit.MONTHS.between(acquisition.exDate(), today);
                    String classification = today.isAfter(acquisition.exDate().plusMonths(12)) ? "LTCG" : "STCG";
                    Map<String, Object> classifiedEntry = Map.of(
                            "stock", holding.stockCode(),
                            "buy_date", acquisition.exDate().toString(),
                            "buy_price", estimatedBuyPrice,
                            "quantity", round2(unmatchedQty),
                            "holding_months", holdingMonths,
                            "gain_or_loss", round2(gainOrLoss),
                            "classification", classification
                    );
                    if ("LTCG".equals(classification)) {
                        ltcgHoldings.add(classifiedEntry);
                        ltcgTotal += gainOrLoss;
                    } else {
                        stcgHoldings.add(classifiedEntry);
                        stcgTotal += gainOrLoss;
                    }
                    if (gainOrLoss < 0) {
                        harvestCandidates.add(buildHarvestCandidate(
                                holding,
                                acquisition.exDate(),
                                estimatedBuyPrice,
                                unmatchedQty,
                                classification,
                                holdingMonths,
                                gainOrLoss
                        ));
                        harvestableLoss += Math.abs(gainOrLoss);
                        if ("LTCG".equals(classification)) {
                            harvestableLtcgLoss += Math.abs(gainOrLoss);
                        } else {
                            harvestableStcgLoss += Math.abs(gainOrLoss);
                        }
                    }
                    continue;
                }

                if (!needsCorporateActionReview.contains(holding.stockCode())) {
                    needsCorporateActionReview.add(holding.stockCode());
                }
                corporateActionReviewReasons.add(Map.of(
                        "stock", holding.stockCode(),
                        "holding_quantity", round2(holding.quantity()),
                        "matched_trade_quantity", round2(matchedQty),
                        "unmatched_quantity", round2(unmatchedQty),
                        "reason", "Unmatched holding quantity could not be classified from trade history. Review corporate actions, allotments, transfers, or pre-history purchases."
                ));

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("stock", holding.stockCode());
                entry.put("buy_price", estimatedBuyPrice);
                entry.put("quantity", round2(unmatchedQty));
                entry.put("gain_or_loss", round2(gainOrLoss));
                entry.put("classification", "UNKNOWN");
                entry.put("source", "estimated_from_avg_price");
                entry.put("note", "Not in trade history — buy date unavailable (IPO allotment, share split, or pre-history purchase). Cannot determine LTCG vs STCG. Excluded from tax totals.");

                if (gainOrLoss < 0) {
                    harvestCandidates.add(Map.of(
                            "stock", holding.stockCode(),
                            "loss_amount", round2(Math.abs(gainOrLoss)),
                            "classification", "UNKNOWN",
                            "source", "estimated_from_avg_price",
                            "note", "Classification requires buy date — verify manually whether LTCG or STCG applies"
                    ));
                    harvestableLoss += Math.abs(gainOrLoss);
                    Map<String, Object> enrichedCandidate = new LinkedHashMap<>(harvestCandidates.get(harvestCandidates.size() - 1));
                    enrichedCandidate.put("quantity", round2(unmatchedQty));
                    enrichedCandidate.put("buy_price", estimatedBuyPrice);
                    enrichedCandidate.put("current_price", round2(holding.currentMarketPrice()));
                    enrichedCandidate.put("holding_period", "UNKNOWN");
                    enrichedCandidate.put("holding_months", null);
                    enrichedCandidate.put("tax_impact", 0.0);
                    harvestCandidates.set(harvestCandidates.size() - 1, enrichedCandidate);
                }

                unmatchedStocks.add(Map.of(
                        "stock", holding.stockCode(),
                        "unmatched_quantity", round2(unmatchedQty),
                        "estimated_buy_price", estimatedBuyPrice,
                        "estimated_gain_or_loss", round2(gainOrLoss),
                        "note", "Excluded from LTCG/STCG totals — buy date unknown. Classify manually based on actual purchase date."
                ));
            }
        }

        return new UnrealizedAnalysis(ltcgTotal, stcgTotal, ltcgHoldings, stcgHoldings,
                harvestCandidates, harvestableLoss, harvestableLtcgLoss, harvestableStcgLoss,
                unmatchedStocks, needsCorporateActionReview, corporateActionReviewReasons);
    }

    TaxTradeInputs buildTaxTradeInputs(
            List<TradeSnapshot> apiTrades,
            List<HoldingSnapshot> holdings,
            LocalDate financialYearStart,
            LocalDate today) {
        List<TradeSnapshot> canonicalApiTrades = apiTrades.stream()
                .map(this::canonicalizeTrade)
                .toList();
        List<TradeSnapshot> iciciTrades = canonicalApiTrades.stream()
                .filter(trade -> !"zerodha".equals(normalizeBrokerLabel(trade.broker())))
                .toList();
        List<TradeSnapshot> zerodhaTrades = canonicalApiTrades.stream()
                .filter(trade -> "zerodha".equals(normalizeBrokerLabel(trade.broker())))
                .toList();

        ZerodhaTradebookService.CoverageSummary tradebookCoverage = zerodhaTradebookService == null
                ? new ZerodhaTradebookService.CoverageSummary(false, null, null, 0, 0, List.of())
                : zerodhaTradebookService.coverageSummary();
        List<TradeSnapshot> combinedTrades = new ArrayList<>(canonicalApiTrades);
        boolean mergeSameDayZerodhaApi = tradebookCoverage.coveredTo() == null || tradebookCoverage.coveredTo().isBefore(today);
        combinedTrades.sort(Comparator.comparing(TradeSnapshot::tradeDate, Comparator.nullsLast(Comparator.naturalOrder())));

        Set<String> participatingBrokers = detectParticipatingBrokers(holdings, canonicalApiTrades, tradebookCoverage);
        boolean zerodhaPresent = participatingBrokers.contains("zerodha");
        LocalDate knownZerodhaActivityStart = firstNonNull(
                firstTradeDate(zerodhaTrades),
                tradebookCoverage.coveredFrom());
        LocalDate knownZerodhaActivityEnd = firstNonNull(
                lastHistoricalTradeDate(today, zerodhaTrades),
                tradebookCoverage.coveredTo());
        LocalDate requiredZerodhaStart = knownZerodhaActivityStart == null || knownZerodhaActivityStart.isBefore(financialYearStart)
                ? financialYearStart
                : knownZerodhaActivityStart;
        LocalDate requiredZerodhaEnd = knownZerodhaActivityEnd == null ? today.minusDays(1) : knownZerodhaActivityEnd;
        boolean zerodhaCovered = !zerodhaPresent || (tradebookCoverage.covers(requiredZerodhaStart, requiredZerodhaEnd)
                && tradebookCoverage.unresolvedAdjustments() == 0);
        if (zerodhaPresent || tradebookCoverage.hasImports()) {
            log.info("Tax harvest Zerodha coverage evaluation: zerodhaPresent={} participatingBrokers={} knownActivityRange={} requiredRange={} tradebookRange={} hasImports={} unresolvedAdjustments={} zerodhaCovered={} sameDayApiMerged={}",
                    zerodhaPresent,
                    participatingBrokers,
                    formatDateRange(knownZerodhaActivityStart, knownZerodhaActivityEnd),
                    formatDateRange(requiredZerodhaStart, requiredZerodhaEnd),
                    formatDateRange(tradebookCoverage.coveredFrom(), tradebookCoverage.coveredTo()),
                    tradebookCoverage.hasImports(),
                    tradebookCoverage.unresolvedAdjustments(),
                    zerodhaCovered,
                    mergeSameDayZerodhaApi);
        }

        List<String> brokersFullyCovered = new ArrayList<>();
        if (participatingBrokers.contains("icici")) {
            brokersFullyCovered.add("icici");
        }
        if (zerodhaPresent && zerodhaCovered) {
            brokersFullyCovered.add("zerodha");
        }
        List<String> brokersPartial = zerodhaPresent && !zerodhaCovered ? List.of("zerodha") : List.of();

        String status = "OK";
        String message = null;
        String recommendation = null;
        if (zerodhaPresent && !zerodhaCovered) {
            boolean hasImports = tradebookCoverage.hasImports();
            status = hasImports ? "PARTIAL" : "NEEDS_ZERODHA_TRADEBOOK";
            if (!hasImports) {
                message = "Zerodha API exposes only same-day trades. Import Zerodha tradebook CSV for accurate FIFO LTCG/STCG calculations.";
            } else if (tradebookCoverage.unresolvedAdjustments() > 0) {
                message = "Imported Zerodha tradebook contains bonus, split, or zero-price adjustments that require manual review. FIFO totals are partial until those rows are reconciled.";
            } else {
                message = "Imported Zerodha tradebook does not cover the required Zerodha activity window for the current portfolio. FIFO LTCG/STCG calculations remain partial for Zerodha.";
            }
            recommendation = "Run import_zerodha_tradebook with a Zerodha Console tradebook CSV covering the current financial year.";
        }

        Map<String, Object> dataSources = new LinkedHashMap<>();
        dataSources.put("trade_history_window_years", tradeHistoryYears);
        dataSources.put("icici_api_trades", iciciTrades.size());
        if (zerodhaTradebookService != null) {
            long sameDayApiTrades = mergeSameDayZerodhaApi
                    ? zerodhaTrades.stream().filter(trade -> today.equals(trade.tradeDate())).count()
                    : 0;
            List<TradeSnapshot> zerodhaExecutedTrades = !zerodhaTrades.isEmpty()
                    ? zerodhaTrades
                    : tradebookCoverage.hasImports()
                    ? zerodhaTradebookService.getImportedTrades(financialYearStart, today)
                    : List.of();
            Map<String, Object> zerodha = new LinkedHashMap<>();
            zerodha.put("imported_trades", tradebookCoverage.importedTrades());
            zerodha.put("same_day_api_trades", sameDayApiTrades);
            zerodha.put("same_day_api_merged", mergeSameDayZerodhaApi);
            zerodha.put("has_imports", tradebookCoverage.hasImports());
            zerodha.put("executed_trades", zerodhaExecutedTrades.stream()
                    .sorted(Comparator.comparing(TradeSnapshot::tradeDate, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(TradeSnapshot::stockCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .map(this::toTradeSummary)
                    .toList());
            if (tradebookCoverage.coveredFrom() != null && tradebookCoverage.coveredTo() != null) {
                zerodha.put("covered_range", Map.of(
                        "from", tradebookCoverage.coveredFrom().toString(),
                        "to", tradebookCoverage.coveredTo().toString()
                ));
            }
            zerodha.put("unresolved_adjustments", tradebookCoverage.unresolvedAdjustments());
            if (!tradebookCoverage.warnings().isEmpty()) {
                zerodha.put("warnings", tradebookCoverage.warnings());
            }
            dataSources.put("zerodha_tradebook", zerodha);
        }

        return new TaxTradeInputs(combinedTrades, new TaxCoverage(
                status,
                message,
                brokersFullyCovered,
                brokersPartial,
                recommendation,
                dataSources
        ));
    }

    private Set<String> detectParticipatingBrokers(
            List<HoldingSnapshot> holdings,
            List<TradeSnapshot> apiTrades,
            ZerodhaTradebookService.CoverageSummary tradebookCoverage) {
        Set<String> brokers = new java.util.LinkedHashSet<>();
        holdings.stream()
                .map(HoldingSnapshot::broker)
                .map(this::normalizeBrokerLabel)
                .forEach(label -> {
                    if ("composite".equals(label)) {
                        brokers.add("icici");
                        brokers.add("zerodha");
                    } else if (label != null && !label.isBlank()) {
                        brokers.add(label);
                    }
                });
        apiTrades.stream()
                .map(TradeSnapshot::broker)
                .map(this::normalizeBrokerLabel)
                .filter(Objects::nonNull)
                .filter(label -> !label.isBlank())
                .filter(label -> !"composite".equals(label))
                .forEach(brokers::add);
        if (tradebookCoverage.hasImports()) {
            brokers.add("zerodha");
        }
        return brokers;
    }

    private TradeSnapshot canonicalizeTrade(TradeSnapshot trade) {
        return new TradeSnapshot(
                stockMetadataService.resolveNseToIcici(trade.stockCode()),
                trade.action(),
                trade.quantity(),
                trade.price(),
                trade.tradeDate(),
                trade.broker()
        );
    }

    private String normalizeBrokerLabel(String broker) {
        if (broker == null || broker.isBlank()) {
            return null;
        }
        return broker.trim().toLowerCase(Locale.ROOT);
    }

    @SafeVarargs
    private final LocalDate firstTradeDate(List<TradeSnapshot>... tradeGroups) {
        return streamTradeDates(tradeGroups)
                .min(LocalDate::compareTo)
                .orElse(null);
    }

    @SafeVarargs
    private final LocalDate lastHistoricalTradeDate(LocalDate today, List<TradeSnapshot>... tradeGroups) {
        return streamTradeDates(tradeGroups)
                .filter(tradeDate -> !tradeDate.isAfter(today.minusDays(1)))
                .max(LocalDate::compareTo)
                .orElse(null);
    }

    @SafeVarargs
    private final java.util.stream.Stream<LocalDate> streamTradeDates(List<TradeSnapshot>... tradeGroups) {
        return java.util.Arrays.stream(tradeGroups)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(TradeSnapshot::tradeDate)
                .filter(Objects::nonNull);
    }

    private String formatDateRange(@Nullable LocalDate from, @Nullable LocalDate to) {
        return from == null || to == null ? "n/a" : from + ".." + to;
    }

    private Map<String, Object> toTradeSummary(TradeSnapshot trade) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("stock", trade.stockCode());
        summary.put("action", trade.action());
        summary.put("quantity", round2(trade.quantity()));
        summary.put("price", round2(trade.price()));
        summary.put("broker", trade.broker());
        if (trade.tradeDate() != null) {
            summary.put("trade_date", trade.tradeDate().toString());
        }
        return summary;
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        return java.util.Arrays.stream(values)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private Set<String> detectGrandfatheringCandidates(
            List<Map<String, Object>> realizedDetails,
            List<Map<String, Object>> unrealizedLtcgHoldings) {
        Set<String> stocks = new java.util.LinkedHashSet<>();
        for (Map<String, Object> detail : realizedDetails) {
            if ("LTCG".equals(detail.get("classification"))) {
                String buyDate = (String) detail.get("buy_date");
                if (buyDate != null && LocalDate.parse(buyDate).isBefore(GRANDFATHERING_CUTOFF)) {
                    stocks.add((String) detail.get("stock"));
                }
            }
        }
        for (Map<String, Object> holding : unrealizedLtcgHoldings) {
            String buyDate = (String) holding.get("buy_date");
            if (buyDate != null && LocalDate.parse(buyDate).isBefore(GRANDFATHERING_CUTOFF)) {
                stocks.add((String) holding.get("stock"));
            }
        }
        return stocks;
    }

    private Map<String, Object> buildHarvestCandidate(
            HoldingSnapshot holding,
            LocalDate buyDate,
            double buyPrice,
            double quantity,
            String classification,
            long holdingMonths,
            double gainOrLoss
    ) {
        double lossAmount = round2(Math.abs(gainOrLoss));
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("stock", holding.stockCode());
        candidate.put("buy_date", buyDate.toString());
        candidate.put("buy_price", round2(buyPrice));
        candidate.put("current_price", round2(holding.currentMarketPrice()));
        candidate.put("quantity", round2(quantity));
        candidate.put("loss_amount", lossAmount);
        candidate.put("classification", classification);
        if (normalizeBrokerLabel(holding.broker()) != null) {
            candidate.put("broker", normalizeBrokerLabel(holding.broker()));
        }
        candidate.put("holding_period", classification);
        candidate.put("holding_months", holdingMonths);
        candidate.put("tax_impact", round2(lossAmount * ("LTCG".equals(classification) ? LTCG_TAX_RATE : STCG_TAX_RATE)));
        return candidate;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record FinancialYearRange(LocalDate start, LocalDate end, String label) {
    }

    private static final class BuyLot {
        final LocalDate date;
        double price;
        double remaining;

        BuyLot(LocalDate date, double price, double quantity) {
            this.date = date;
            this.price = price;
            this.remaining = quantity;
        }
    }

    private record RealizedGains(double ltcg, double stcg, List<Map<String, Object>> details) {
    }

    private record UnrealizedAnalysis(
            double ltcg,
            double stcg,
            List<Map<String, Object>> ltcgHoldings,
            List<Map<String, Object>> stcgHoldings,
            List<Map<String, Object>> harvestCandidates,
            double totalHarvestableLoss,
            double harvestableLtcgLoss,
            double harvestableStcgLoss,
            List<Map<String, Object>> unmatchedStocks,
            List<String> needsCorporateActionReview,
            List<Map<String, Object>> corporateActionReviewReasons
    ) {
    }

    record TaxTradeInputs(
            List<TradeSnapshot> trades,
            TaxCoverage coverage
    ) {
    }

    record TaxCoverage(
            String status,
            String message,
            List<String> brokersFullyCovered,
            List<String> brokersPartial,
            String recommendation,
            Map<String, Object> dataSources
    ) {
    }

    private record FifoProcessingResult(
            Map<String, List<BuyLot>> lotsByStock,
            RealizedGains realizedGains
    ) {
    }

    private record TimelineEvent(
            LocalDate date,
            int sortOrder,
            String stockCode,
            TradeSnapshot trade,
            CorporateActionService.CorporateAction corporateAction
    ) {
    }
}
