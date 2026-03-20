package com.broker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

public class Funds {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GetFundsResponse(
            @JsonProperty("Success") Success success,
            @JsonProperty("Status") int status,
            @JsonProperty("Error") String error
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Success(
                @JsonProperty("bank_account") String bankAccount,
                @JsonProperty("total_balance") String totalBalance,
                @JsonProperty("allocated_equity") String allocatedEquity,
                @JsonProperty("allocated_fno") String allocatedFno,
                @JsonProperty("allocated_commodity") String allocatedCommodity,
                @JsonProperty("allocated_currency") String allocatedCurrency,
                @JsonProperty("block_by_trade_equity") String blockByTradeEquity,
                @JsonProperty("block_by_trade_fno") String blockByTradeFno,
                @JsonProperty("block_by_trade_commodity") String blockByTradeCommodity,
                @JsonProperty("block_by_trade_currency") String blockByTradeCurrency,
                @JsonProperty("unallocated_balance") String unallocatedBalance
        ) {}
    }

    public record SetFundsRequest(
            @JsonProperty("transaction_type") String transactionType,
            @JsonProperty("amount") String amount,
            @JsonProperty("segment") String segment
    ) {
        public static SetFundsRequest credit(String amount, String segment) {
            return new SetFundsRequest("Credit", amount, segment);
        }

        public static SetFundsRequest debit(String amount, String segment) {
            return new SetFundsRequest("Debit", amount, segment);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SetFundsResponse(
            @JsonProperty("Success") Success success,
            @JsonProperty("Status") int status,
            @JsonProperty("Error") String error
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Success(
                @JsonProperty("message") String message
        ) {}
    }
}
