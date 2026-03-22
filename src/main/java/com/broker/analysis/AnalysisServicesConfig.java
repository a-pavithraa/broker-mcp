package com.broker.analysis;

import com.broker.gateway.BrokerDataProvider;
import com.broker.gateway.zerodha.ZerodhaTradebookService;
import com.broker.reference.CorporateActionService;
import com.broker.reference.StockMetadataService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;

@Configuration
class AnalysisServicesConfig {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Bean
    Clock indiaClock() {
        return Clock.system(INDIA);
    }

    @Bean
    MarketDataService marketDataService(
            BrokerDataProvider dataProvider,
            StockMetadataService stockMetadataService,
            Clock indiaClock,
            @Qualifier("brokerIoExecutor") ExecutorService brokerIoExecutor
    ) {
        return new MarketDataService(dataProvider, stockMetadataService, indiaClock, brokerIoExecutor);
    }

    @Bean
    OrderManagementService orderManagementService(
            BrokerDataProvider dataProvider,
            StockMetadataService stockMetadataService,
            ObjectMapper objectMapper,
            Clock indiaClock,
            @Value("${broker.trading.enabled:false}") boolean tradingEnabled,
            @Value("${broker.trading.max-order-value:500000}") double maxOrderValue,
            @Value("${broker.trading.log-path:logs/trade-executions.log}") String tradeLogPath
    ) {
        return new OrderManagementService(
                dataProvider,
                stockMetadataService,
                objectMapper,
                tradingEnabled,
                maxOrderValue,
                indiaClock,
                Path.of(tradeLogPath)
        );
    }

    @Bean
    PortfolioAnalysisService portfolioAnalysisService(StockMetadataService stockMetadataService) {
        return new PortfolioAnalysisService(stockMetadataService);
    }

    @Bean
    TaxHarvestService taxHarvestService(
            StockMetadataService stockMetadataService,
            CorporateActionService corporateActionService,
            ObjectProvider<ZerodhaTradebookService> zerodhaTradebookServiceProvider,
            @Value("${broker.trade-history-years:5}") int tradeHistoryYears
    ) {
        return new TaxHarvestService(
                stockMetadataService,
                corporateActionService,
                zerodhaTradebookServiceProvider.getIfAvailable(),
                tradeHistoryYears
        );
    }
}
