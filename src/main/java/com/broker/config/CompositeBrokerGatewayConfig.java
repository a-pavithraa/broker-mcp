package com.broker.config;

import com.broker.gateway.BrokerDataProvider;
import com.broker.gateway.CompositeBrokerGateway;
import com.broker.reference.StockMetadataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class CompositeBrokerGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(CompositeBrokerGatewayConfig.class);

    @Bean(destroyMethod = "shutdown")
    ExecutorService brokerIoExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("broker-io-", 0).factory());
    }

    @Bean
    @Primary
    @Profile("!demo")
    @ConditionalOnBean(name = {"breezeGatewayService", "zerodhaGatewayService"})
    CompositeBrokerGateway compositeBrokerGateway(
            @Qualifier("breezeGatewayService") BrokerDataProvider breezeGatewayService,
            @Qualifier("zerodhaGatewayService") BrokerDataProvider zerodhaGatewayService,
            @Qualifier("brokerIoExecutor") ExecutorService brokerIoExecutor,
            StockMetadataService stockMetadataService,
            @Value("${broker.composite.preferred-data-broker:}") String preferredDataBroker,
            @Value("${zerodha.tier:free}") String zerodhaTier,
            @Value("${broker.composite.default-order-broker:zerodha}") String defaultOrderBroker) {
        String resolvedPreferredDataBroker = resolvePreferredDataBroker(preferredDataBroker, zerodhaTier);
        log.info("Creating composite broker gateway: configuredPreferredDataBroker='{}', zerodhaTier='{}', resolvedPreferredDataBroker='{}', defaultOrderBroker='{}'",
                preferredDataBroker, zerodhaTier, resolvedPreferredDataBroker, defaultOrderBroker);
        return new CompositeBrokerGateway(
                breezeGatewayService,
                zerodhaGatewayService,
                brokerIoExecutor,
                stockMetadataService,
                resolvedPreferredDataBroker,
                defaultOrderBroker
        );
    }

    private String resolvePreferredDataBroker(String preferredDataBroker, String zerodhaTier) {
        String configuredBroker = normalize(preferredDataBroker);
        if (!configuredBroker.isBlank()) {
            return configuredBroker;
        }
        return "free".equals(normalize(zerodhaTier)) ? "icici" : "zerodha";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
