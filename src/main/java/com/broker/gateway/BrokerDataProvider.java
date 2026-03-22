package com.broker.gateway;

import com.broker.model.AnalysisModels.*;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface BrokerDataProvider {

    List<HoldingSnapshot> getPortfolioHoldings();

    FundsSnapshot getFunds();

    default List<FundsSnapshot> getAllFunds() {
        return List.of(getFunds());
    }

    QuoteSnapshot getQuote(String stockCode, String exchangeCode, String productType);

    Map<String, QuoteSnapshot> getQuotes(List<String> stockCodes, String exchangeCode, String productType);

    List<HistoricalCandle> getHistoricalCharts(
            String stockCode,
            String exchangeCode,
            String productType,
            String interval,
            LocalDate fromDate,
            LocalDate toDate
    );

    List<TradeSnapshot> getTrades(LocalDate fromDate, LocalDate toDate);

    List<GttOrderSnapshot> getGttOrders();

    List<OptionChainSnapshot> getOptionChain(String stockCode, String expiryDate, String right);

    JsonNode previewOrder(Map<String, String> body);

    JsonNode placeOrder(Map<String, String> body);

    JsonNode getOrderDetail(String exchangeCode, String orderId);

    JsonNode placeGttOrder(Map<String, String> body);
}
