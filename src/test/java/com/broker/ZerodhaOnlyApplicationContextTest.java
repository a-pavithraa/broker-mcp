package com.broker;

import com.broker.model.AnalysisModels.*;
import com.broker.gateway.icici.BreezeApiClient;
import com.broker.gateway.icici.BreezeSessionManager;
import com.broker.gateway.BrokerDataProvider;
import com.broker.gateway.zerodha.ZerodhaSessionManager;
import com.broker.tools.SessionTools;
import com.broker.tools.ZerodhaSessionWriteTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(
        classes = {BrokerMcpApplication.class, ZerodhaOnlyApplicationContextTest.TestConfig.class},
        properties = {
                "breeze.enabled=false",
                "zerodha.enabled=true"
        }
)
class ZerodhaOnlyApplicationContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoadsWithoutBreezeBeans() {
        assertEquals(0, applicationContext.getBeansOfType(BreezeSessionManager.class).size());
        assertEquals(0, applicationContext.getBeansOfType(BreezeApiClient.class).size());
        assertEquals(1, applicationContext.getBeansOfType(ZerodhaSessionManager.class).size());
        assertFalse(applicationContext.getBeansOfType(SessionTools.class).isEmpty());
        assertFalse(applicationContext.getBeansOfType(ZerodhaSessionWriteTools.class).isEmpty());
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        BrokerDataProvider brokerDataProvider() {
            return new StubBrokerDataProvider();
        }
    }

    static final class StubBrokerDataProvider implements BrokerDataProvider {

        @Override
        public List<HoldingSnapshot> getPortfolioHoldings() {
            return List.of();
        }

        @Override
        public FundsSnapshot getFunds() {
            return new FundsSnapshot(0, 0, "", "stub", Map.of());
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
            return List.of();
        }

        @Override
        public List<GttOrderSnapshot> getGttOrders() {
            return List.of();
        }

        @Override
        public List<OptionChainSnapshot> getOptionChain(String stockCode, String expiryDate, String right) {
            return List.of();
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
