package com.broker.tools;

import com.broker.model.AnalysisModels.*;
import com.broker.gateway.BrokerDataProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FundsToolsTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void getFunds_shouldRenderSingleBrokerSnapshot() throws Exception {
        FundsTools tools = new FundsTools(
                new StubBrokerDataProvider(List.of(
                        new FundsSnapshot(12_500, 3_000, "1234", "icici", Map.of("allocated_equity", "5000"))
                )),
                objectMapper
        );

        Map<String, Object> result = objectMapper.readValue(tools.getFunds(), Map.class);
        List<Map<String, Object>> funds = (List<Map<String, Object>>) result.get("funds");

        assertEquals("OK", result.get("status"));
        assertEquals(1, funds.size());
        assertEquals("icici", funds.getFirst().get("broker"));
        assertEquals("1234", funds.getFirst().get("bank_account"));
        assertEquals("5000", ((Map<?, ?>) funds.getFirst().get("details")).get("allocated_equity"));
    }

    @Test
    void getFunds_shouldRenderMultipleBrokersSeparately() throws Exception {
        FundsTools tools = new FundsTools(
                new StubBrokerDataProvider(List.of(
                        new FundsSnapshot(12_500, 3_000, "1234", "icici", Map.of("allocated_equity", "5000")),
                        new FundsSnapshot(8_000, 2_500, "", "zerodha", Map.of("collateral", "1500", "span_used", "250"))
                )),
                objectMapper
        );

        Map<String, Object> result = objectMapper.readValue(tools.getFunds(), Map.class);
        List<Map<String, Object>> funds = (List<Map<String, Object>>) result.get("funds");

        assertEquals(2, funds.size());
        assertEquals("icici", funds.get(0).get("broker"));
        assertEquals("zerodha", funds.get(1).get("broker"));
        assertEquals(false, funds.get(1).containsKey("bank_account"));
        assertEquals("1500", ((Map<?, ?>) funds.get(1).get("details")).get("collateral"));
    }

    private static final class StubBrokerDataProvider implements BrokerDataProvider {

        private final List<FundsSnapshot> funds;

        private StubBrokerDataProvider(List<FundsSnapshot> funds) {
            this.funds = funds;
        }

        @Override
        public List<HoldingSnapshot> getPortfolioHoldings() {
            throw new UnsupportedOperationException();
        }

        @Override
        public FundsSnapshot getFunds() {
            return funds.getFirst();
        }

        @Override
        public List<FundsSnapshot> getAllFunds() {
            return funds;
        }

        @Override
        public QuoteSnapshot getQuote(String stockCode, String exchangeCode, String productType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, QuoteSnapshot> getQuotes(List<String> stockCodes, String exchangeCode, String productType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<HistoricalCandle> getHistoricalCharts(String stockCode, String exchangeCode, String productType, String interval, LocalDate fromDate, LocalDate toDate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TradeSnapshot> getTrades(LocalDate fromDate, LocalDate toDate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<GttOrderSnapshot> getGttOrders() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<OptionChainSnapshot> getOptionChain(String stockCode, String expiryDate, String right) {
            throw new UnsupportedOperationException();
        }

        @Override
        public tools.jackson.databind.JsonNode previewOrder(Map<String, String> body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public tools.jackson.databind.JsonNode placeOrder(Map<String, String> body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public tools.jackson.databind.JsonNode getOrderDetail(String exchangeCode, String orderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public tools.jackson.databind.JsonNode placeGttOrder(Map<String, String> body) {
            throw new UnsupportedOperationException();
        }
    }
}
