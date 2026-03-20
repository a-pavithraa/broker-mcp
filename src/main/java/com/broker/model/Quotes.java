package com.broker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class Quotes {

    public record QuotesRequest(
            String stockCode,
            String exchangeCode,
            String productType,
            String expiryDate,
            String right,
            String strikePrice
    ) {
        public static QuotesRequest forCash(String stockCode, String exchangeCode) {
            return new QuotesRequest(stockCode, exchangeCode, "cash", null, "others", null);
        }

        public static QuotesRequest forFutures(String stockCode, String exchangeCode, String expiryDate) {
            return new QuotesRequest(stockCode, exchangeCode, "futures", expiryDate, "others", null);
        }

        public static QuotesRequest forOptions(String stockCode, String exchangeCode, String expiryDate, String right, String strikePrice) {
            return new QuotesRequest(stockCode, exchangeCode, "options", expiryDate, right, strikePrice);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuotesResponse(
            @JsonProperty("Success") List<Quote> success,
            @JsonProperty("Status") int status,
            @JsonProperty("Error") String error
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Quote(
            @JsonProperty("stock_code") String stockCode,
            @JsonProperty("exchange_code") String exchangeCode,
            @JsonProperty("product_type") String productType,
            @JsonProperty("ltp") String ltp,
            @JsonProperty("best_bid_price") String bestBidPrice,
            @JsonProperty("best_bid_quantity") String bestBidQuantity,
            @JsonProperty("best_offer_price") String bestOfferPrice,
            @JsonProperty("best_offer_quantity") String bestOfferQuantity,
            @JsonProperty("open") String open,
            @JsonProperty("high") String high,
            @JsonProperty("low") String low,
            @JsonProperty("previous_close") String previousClose,
            @JsonProperty("ltt") String ltt,
            @JsonProperty("total_quantity_traded") String totalQuantityTraded,
            @JsonProperty("upper_circuit") String upperCircuit,
            @JsonProperty("lower_circuit") String lowerCircuit,
            @JsonProperty("spot_price") String spotPrice
    ) {}
}
