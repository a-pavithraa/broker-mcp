package com.broker.analysis;

import com.broker.gateway.BrokerDataProvider;
import com.broker.reference.CorporateActionService;
import com.broker.reference.StockMetadataService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisServicesConfigTest {

    @Test
    void shouldRegisterCompoundToolCollaboratorsAsBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestDependencies.class, AnalysisServicesConfig.class)
                .withBean(CompoundToolService.class)
                .withPropertyValues(
                        "broker.trading.enabled=false",
                        "broker.trading.max-order-value=500000",
                        "broker.trading.log-path=logs/test-trade.log",
                        "broker.trade-history-years=5"
                )
                .run(context -> {
                    assertTrue(context.containsBean("indiaClock"));
                    assertTrue(context.containsBean("marketDataService"));
                    assertTrue(context.containsBean("orderManagementService"));
                    assertTrue(context.containsBean("portfolioAnalysisService"));
                    assertTrue(context.containsBean("taxHarvestService"));
                    assertTrue(context.containsBean("compoundToolService"));
                });
    }

    @Configuration
    static class TestDependencies {

        @Bean(name = "brokerIoExecutor", destroyMethod = "shutdown")
        ExecutorService brokerIoExecutor() {
            return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("test-broker-io-", 0).factory());
        }

        @Bean
        BrokerDataProvider brokerDataProvider() {
            return new StubBrokerDataProvider();
        }

        @Bean
        StockMetadataService stockMetadataService() {
            return new StockMetadataService(new DefaultResourceLoader(), objectMapper(), "classpath:stock-universe.csv");
        }

        @Bean
        CorporateActionService corporateActionService() {
            return new CorporateActionService(
                    new DefaultResourceLoader(),
                    objectMapper(),
                    stockMetadataService(),
                    "classpath:stock-corporate-actions.json",
                    "target/test-corporate-actions-config.json",
                    java.time.Clock.system(java.time.ZoneId.of("Asia/Kolkata"))
            );
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }

    static final class StubBrokerDataProvider implements BrokerDataProvider {

        @Override
        public List<com.broker.model.AnalysisModels.HoldingSnapshot> getPortfolioHoldings() {
            return List.of();
        }

        @Override
        public com.broker.model.AnalysisModels.FundsSnapshot getFunds() {
            return new com.broker.model.AnalysisModels.FundsSnapshot(0, 0, "", "stub", Map.of());
        }

        @Override
        public com.broker.model.AnalysisModels.QuoteSnapshot getQuote(String stockCode, String exchangeCode, String productType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, com.broker.model.AnalysisModels.QuoteSnapshot> getQuotes(List<String> stockCodes, String exchangeCode, String productType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.broker.model.AnalysisModels.HistoricalCandle> getHistoricalCharts(
                String stockCode,
                String exchangeCode,
                String productType,
                String interval,
                LocalDate fromDate,
                LocalDate toDate
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.broker.model.AnalysisModels.TradeSnapshot> getTrades(LocalDate fromDate, LocalDate toDate) {
            return List.of();
        }

        @Override
        public List<com.broker.model.AnalysisModels.GttOrderSnapshot> getGttOrders() {
            return List.of();
        }

        @Override
        public List<com.broker.model.AnalysisModels.OptionChainSnapshot> getOptionChain(String stockCode, String expiryDate, String right) {
            return List.of();
        }

        @Override
        public JsonNode previewOrder(Map<String, String> body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonNode placeOrder(Map<String, String> body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonNode getOrderDetail(String exchangeCode, String orderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonNode placeGttOrder(Map<String, String> body) {
            throw new UnsupportedOperationException();
        }
    }
}
