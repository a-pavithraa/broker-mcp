package com.broker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class DematHoldings {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DematHoldingsResponse(
            @JsonProperty("Success") List<Holding> success,
            @JsonProperty("Status") int status,
            @JsonProperty("Error") String error
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Holding(
            @JsonProperty("stock_code") String stockCode,
            @JsonProperty("stock_ISIN") String stockIsin,
            @JsonProperty("quantity") String quantity,
            @JsonProperty("total_bulk_quantity") String totalBulkQuantity,
            @JsonProperty("available_quantity") String availableQuantity,
            @JsonProperty("blocked_quantity") String blockedQuantity,
            @JsonProperty("allocated_quantity") String allocatedQuantity
    ) {}
}
