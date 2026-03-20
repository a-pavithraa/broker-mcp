package com.broker.model;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

public final class AnalysisModels {

    private AnalysisModels() {
    }

    public record HoldingSnapshot(
            String stockCode,
            String stockName,
            String exchangeCode,
            double quantity,
            double averagePrice,
            double currentMarketPrice,
            double bookedProfitLoss,
            double changePercentage,
            String broker,
            String isin
    ) {
    }

    public record QuoteSnapshot(
            String stockCode,
            String exchangeCode,
            double ltp,
            double previousClose,
            double open,
            double high,
            double low,
            double volume,
            double bestBidPrice,
            double bestOfferPrice,
            ZonedDateTime lastTradeTime
    ) {
    }

    public record HistoricalCandle(
            ZonedDateTime dateTime,
            double open,
            double high,
            double low,
            double close,
            double volume,
            double openInterest
    ) {
    }

    public record FundsSnapshot(
            double totalBalance,
            double unallocatedBalance,
            String bankAccount,
            String broker,
            Map<String, String> details
    ) {
    }

    public record TradeSnapshot(
            String stockCode,
            String action,
            double quantity,
            double price,
            LocalDate tradeDate,
            String broker
    ) {
    }

    public record GttOrderSnapshot(
            String orderId,
            String stockCode,
            double quantity,
            String broker
    ) {
    }

    public record OptionChainSnapshot(
            double strikePrice,
            double openInterest
    ) {
    }

    public record StockMetadata(
            String code,
            String name,
            String sector,
            String group,
            String nseSymbol,
            String isin,
            List<String> aliases
    ) {
    }

    public record ResolvedStock(
            String code,
            String name,
            String sector,
            String group,
            String nseSymbol,
            String isin
    ) {
    }
}
