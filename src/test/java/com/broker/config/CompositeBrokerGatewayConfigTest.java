package com.broker.config;

import com.broker.model.AnalysisModels.*;
import com.broker.gateway.BrokerDataProvider;
import com.broker.gateway.CompositeBrokerGateway;
import com.broker.reference.StockMetadataService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CompositeBrokerGatewayConfigTest {

    @Test
    void shouldCreateCompositeBrokerGatewayOnlyWhenBothConcreteGatewaysExist() {
        new ApplicationContextRunner()
                .withUserConfiguration(BothBrokersConfig.class, CompositeBrokerGatewayConfig.class)
                .run(context -> {
                    BrokerDataProvider provider = context.getBean(BrokerDataProvider.class);
                    assertInstanceOf(CompositeBrokerGateway.class, provider);
                });

        new ApplicationContextRunner()
                .withUserConfiguration(OnlyBreezeConfig.class, CompositeBrokerGatewayConfig.class)
                .run(context -> org.junit.jupiter.api.Assertions.assertFalse(context.containsBean("compositeBrokerGateway")));
    }

    @Test
    void shouldPreferIciciWhenZerodhaIsFreeAndNoExplicitPreferenceIsSet() {
        new ApplicationContextRunner()
                .withUserConfiguration(BothBrokersConfig.class, CompositeBrokerGatewayConfig.class)
                .withPropertyValues("zerodha.tier=free")
                .run(context -> {
                    CompositeBrokerGateway gateway = context.getBean(CompositeBrokerGateway.class);
                    assertEquals("icici", readField(gateway, "preferredDataBroker"));
                });
    }

    @Test
    void shouldPreferZerodhaWhenZerodhaIsPaidAndNoExplicitPreferenceIsSet() {
        new ApplicationContextRunner()
                .withUserConfiguration(BothBrokersConfig.class, CompositeBrokerGatewayConfig.class)
                .withPropertyValues("zerodha.tier=paid")
                .run(context -> {
                    CompositeBrokerGateway gateway = context.getBean(CompositeBrokerGateway.class);
                    assertEquals("zerodha", readField(gateway, "preferredDataBroker"));
                });
    }

    @Test
    void shouldRespectExplicitPreferredBrokerOverride() {
        new ApplicationContextRunner()
                .withUserConfiguration(BothBrokersConfig.class, CompositeBrokerGatewayConfig.class)
                .withPropertyValues(
                        "zerodha.tier=free",
                        "broker.composite.preferred-data-broker=zerodha"
                )
                .run(context -> {
                    CompositeBrokerGateway gateway = context.getBean(CompositeBrokerGateway.class);
                    assertEquals("zerodha", readField(gateway, "preferredDataBroker"));
                });
    }

    @Test
    void shouldUseVirtualThreadsForCompositeBrokerWork() {
        new ApplicationContextRunner()
                .withUserConfiguration(BothBrokersConfig.class, CompositeBrokerGatewayConfig.class)
                .run(context -> {
                    ExecutorService executor = context.getBean(ExecutorService.class);
                    try {
                        assertTrue(executor.submit(() -> Thread.currentThread().isVirtual()).get(1, TimeUnit.SECONDS));
                    } catch (Exception ex) {
                        throw new AssertionError(ex);
                    }
                });
    }

    @Configuration
    static class BothBrokersConfig {
        @Bean
        BrokerDataProvider breezeGatewayService() {
            return new StubBrokerDataProvider();
        }

        @Bean
        BrokerDataProvider zerodhaGatewayService() {
            return new StubBrokerDataProvider();
        }

        @Bean
        StockMetadataService stockMetadataService() {
            return new StockMetadataService(new DefaultResourceLoader(), JsonMapper.builder().build(), "classpath:stock-universe.csv");
        }
    }

    @Configuration
    static class OnlyBreezeConfig {
        @Bean
        BrokerDataProvider breezeGatewayService() {
            return new StubBrokerDataProvider();
        }

        @Bean
        StockMetadataService stockMetadataService() {
            return new StockMetadataService(new DefaultResourceLoader(), JsonMapper.builder().build(), "classpath:stock-universe.csv");
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

    private static String readField(CompositeBrokerGateway gateway, String fieldName) {
        try {
            Field field = CompositeBrokerGateway.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (String) field.get(gateway);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
