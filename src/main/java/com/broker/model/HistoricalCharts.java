package com.broker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class HistoricalCharts {

    public record HistoricalChartsRequest(
            String stockCode,
            String exchangeCode,
            String productType,
            String interval,
            String fromDate,
            String toDate,
            String expiryDate,
            String right,
            String strikePrice
    ) {
        public static HistoricalChartsRequest forCash(
                String stockCode,
                String exchangeCode,
                String interval,
                String fromDate,
                String toDate) {
            return new HistoricalChartsRequest(
                    stockCode, exchangeCode, "cash", interval, fromDate, toDate, null, null, null
            );
        }

        public static HistoricalChartsRequest forFutures(
                String stockCode,
                String exchangeCode,
                String interval,
                String fromDate,
                String toDate,
                String expiryDate) {
            return new HistoricalChartsRequest(
                    stockCode, exchangeCode, "futures", interval, fromDate, toDate, expiryDate, null, null
            );
        }

        public static HistoricalChartsRequest forOptions(
                String stockCode,
                String exchangeCode,
                String interval,
                String fromDate,
                String toDate,
                String expiryDate,
                String right,
                String strikePrice) {
            return new HistoricalChartsRequest(
                    stockCode, exchangeCode, "options", interval, fromDate, toDate, expiryDate, right, strikePrice
            );
        }

        public boolean isValidInterval() {
            return interval != null && List.of("1minute", "5minute", "30minute", "1day").contains(interval.toLowerCase());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HistoricalChartsResponse(
            @JsonProperty("Success") List<OHLCV> success,
            @JsonProperty("Status") int status,
            @JsonProperty("Error") String error
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OHLCV(
            @JsonProperty("datetime") String datetime,
            @JsonProperty("stock_code") String stockCode,
            @JsonProperty("exchange_code") String exchangeCode,
            @JsonProperty("open") String open,
            @JsonProperty("high") String high,
            @JsonProperty("low") String low,
            @JsonProperty("close") String close,
            @JsonProperty("volume") String volume,
            @JsonProperty("open_interest") String openInterest
    ) {}
}
