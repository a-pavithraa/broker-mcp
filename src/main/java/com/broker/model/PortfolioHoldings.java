package com.broker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class PortfolioHoldings {

    public record PortfolioHoldingsRequest(
            @JsonProperty("exchange_code") String exchangeCode,
            @JsonProperty("from_date") String fromDate,
            @JsonProperty("to_date") String toDate,
            @JsonProperty("stock_code") String stockCode,
            @JsonProperty("portfolio_type") String portfolioType
    ) {
        public static PortfolioHoldingsRequest of(String exchangeCode, String fromDate, String toDate) {
            return new PortfolioHoldingsRequest(exchangeCode, fromDate, toDate, null, null);
        }

        public static PortfolioHoldingsRequest of(String exchangeCode, String fromDate, String toDate, String stockCode) {
            return new PortfolioHoldingsRequest(exchangeCode, fromDate, toDate, stockCode, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PortfolioHoldingsResponse(
            @JsonProperty("Success") List<Holding> success,
            @JsonProperty("Status") int status,
            @JsonProperty("Error") String error
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Holding(
            @JsonProperty("stock_code") String stockCode,
            @JsonProperty("exchange_code") String exchangeCode,
            @JsonProperty("quantity") String quantity,
            @JsonProperty("average_price") String averagePrice,
            @JsonProperty("booked_profit_loss") String bookedProfitLoss,
            @JsonProperty("current_market_price") String currentMarketPrice,
            @JsonProperty("change_percentage") String changePercentage,
            @JsonProperty("product_type") String productType,
            @JsonProperty("expiry_date") String expiryDate,
            @JsonProperty("strike_price") String strikePrice,
            @JsonProperty("right") String right
    ) {}
}
