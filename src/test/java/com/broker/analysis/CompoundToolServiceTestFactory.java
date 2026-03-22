package com.broker.analysis;

import com.broker.gateway.BrokerDataProvider;
import com.broker.gateway.zerodha.ZerodhaTradebookService;
import com.broker.reference.CorporateActionService;
import com.broker.reference.StockMetadataService;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.lang.Nullable;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CompoundToolServiceTestFactory {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private CompoundToolServiceTestFactory() {
    }

    public static Builder builder(
            BrokerDataProvider dataProvider,
            StockMetadataService stockMetadataService,
            ObjectMapper objectMapper
    ) {
        return new Builder(dataProvider, stockMetadataService, objectMapper);
    }

    public static final class Builder {

        private final BrokerDataProvider dataProvider;
        private final StockMetadataService stockMetadataService;
        private final ObjectMapper objectMapper;
        private boolean tradingEnabled;
        private double maxOrderValue = 500000;
        private String tradeLogPath = "logs/test-trade.log";
        private int tradeHistoryYears = 5;
        private Clock clock = Clock.system(INDIA);
        private @Nullable ExecutorService analysisExecutor;
        private @Nullable ZerodhaTradebookService zerodhaTradebookService;

        private Builder(
                BrokerDataProvider dataProvider,
                StockMetadataService stockMetadataService,
                ObjectMapper objectMapper
        ) {
            this.dataProvider = dataProvider;
            this.stockMetadataService = stockMetadataService;
            this.objectMapper = objectMapper;
        }

        public Builder tradingEnabled(boolean tradingEnabled) {
            this.tradingEnabled = tradingEnabled;
            return this;
        }

        public Builder maxOrderValue(double maxOrderValue) {
            this.maxOrderValue = maxOrderValue;
            return this;
        }

        public Builder tradeLogPath(String tradeLogPath) {
            this.tradeLogPath = tradeLogPath;
            return this;
        }

        public Builder tradeHistoryYears(int tradeHistoryYears) {
            this.tradeHistoryYears = tradeHistoryYears;
            return this;
        }

        public Builder clock(@Nullable Clock clock) {
            this.clock = clock == null ? Clock.system(INDIA) : clock.withZone(INDIA);
            return this;
        }

        public Builder analysisExecutor(@Nullable ExecutorService analysisExecutor) {
            this.analysisExecutor = analysisExecutor;
            return this;
        }

        public Builder zerodhaTradebookService(@Nullable ZerodhaTradebookService zerodhaTradebookService) {
            this.zerodhaTradebookService = zerodhaTradebookService;
            return this;
        }

        public CompoundToolService.Dependencies dependencies() {
            ExecutorService resolvedExecutor = analysisExecutor != null
                    ? analysisExecutor
                    : Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("compound-analysis-test-", 0).factory());
            return new CompoundToolService.Dependencies(
                    dataProvider,
                    objectMapper,
                    tradeHistoryYears,
                    clock,
                    resolvedExecutor,
                    analysisExecutor == null,
                    new TaxHarvestService(
                            stockMetadataService,
                            new CorporateActionService(
                                    new DefaultResourceLoader(),
                                    objectMapper,
                                    stockMetadataService,
                                    "classpath:stock-corporate-actions.json",
                                    Path.of("target", "test-corporate-actions-" + System.nanoTime() + ".json").toString(),
                                    clock
                            ),
                            zerodhaTradebookService,
                            tradeHistoryYears
                    ),
                    new PortfolioAnalysisService(stockMetadataService),
                    new OrderManagementService(
                            dataProvider,
                            stockMetadataService,
                            objectMapper,
                            tradingEnabled,
                            maxOrderValue,
                            clock,
                            Path.of(tradeLogPath)
                    ),
                    new MarketDataService(dataProvider, stockMetadataService, clock, resolvedExecutor)
            );
        }

        public CompoundToolService build() {
            return new CompoundToolService(dependencies());
        }
    }
}
