package com.broker.gateway.zerodha;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.broker.config.ZerodhaTradebookProperties;
import com.broker.model.AnalysisModels;
import com.broker.reference.StockMetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ZerodhaTradebookServiceTest {

    private ObjectMapper objectMapper;
    private StockMetadataService stockMetadataService;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        stockMetadataService = new StockMetadataService(new DefaultResourceLoader(), objectMapper, "classpath:stock-universe.csv");
    }

    @Test
    void importTradebookShouldPersistTradesAndFlagAdjustments(@TempDir Path tempDir) throws Exception {
        Path importRoot = Files.createDirectories(tempDir.resolve("imports"));
        Path storePath = tempDir.resolve("data").resolve("zerodha-tradebook.json");
        Path csvPath = importRoot.resolve("zerodha-tradebook.csv");
        Files.writeString(csvPath, """
                Trade Date,Trading Symbol,Trade Type,Quantity,Price,Exchange,Segment,Order ID,Trade ID
                01/04/2025,INFY,BUY,5,1500,NSE,EQ,OID1,TID1
                05/04/2025,INFY,SELL,2,1600,NSE,EQ,OID2,TID2
                10/04/2025,INFY,BONUS,3,0,NSE,EQ,OID3,TID3
                """);

        ZerodhaTradebookService service = new ZerodhaTradebookService(
                new ZerodhaTradebookProperties(importRoot.toString(), storePath.toString()),
                stockMetadataService,
                objectMapper,
                Clock.fixed(Instant.parse("2026-03-17T04:00:00Z"), ZoneId.of("Asia/Kolkata"))
        );

        ZerodhaTradebookService.ImportSummary summary = service.importTradebook(csvPath.toString());
        ZerodhaTradebookService.CoverageSummary coverage = service.coverageSummary();
        List<AnalysisModels.TradeSnapshot> trades =
                service.getImportedTrades(LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30));

        assertEquals(2, summary.importedTrades());
        assertEquals(1, summary.storedAdjustments());
        assertFalse(summary.warnings().isEmpty());
        assertTrue(Files.exists(storePath));

        assertTrue(coverage.hasImports());
        assertEquals(LocalDate.of(2025, 4, 1), coverage.coveredFrom());
        assertEquals(LocalDate.of(2025, 4, 10), coverage.coveredTo());
        assertEquals(2, coverage.importedTrades());
        assertEquals(1, coverage.unresolvedAdjustments());
        assertEquals(2, trades.size());
    }

    @Test
    void coverageSummaryShouldLogReloadedStoreState(@TempDir Path tempDir) throws Exception {
        Path importRoot = Files.createDirectories(tempDir.resolve("imports"));
        Path storePath = tempDir.resolve("data").resolve("zerodha-tradebook.json");
        Path csvPath = importRoot.resolve("zerodha-tradebook.csv");
        Files.writeString(csvPath, """
                Trade Date,Trading Symbol,Trade Type,Quantity,Price,Exchange,Segment,Order ID,Trade ID
                01/04/2025,INFY,BUY,5,1500,NSE,EQ,OID1,TID1
                05/04/2025,INFY,SELL,2,1600,NSE,EQ,OID2,TID2
                """);

        ZerodhaTradebookService writer = new ZerodhaTradebookService(
                new ZerodhaTradebookProperties(importRoot.toString(), storePath.toString()),
                stockMetadataService,
                objectMapper,
                Clock.fixed(Instant.parse("2026-03-17T04:00:00Z"), ZoneId.of("Asia/Kolkata"))
        );
        writer.importTradebook(csvPath.toString());

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ZerodhaTradebookService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ZerodhaTradebookService reader = new ZerodhaTradebookService(
                    new ZerodhaTradebookProperties(importRoot.toString(), storePath.toString()),
                    stockMetadataService,
                    objectMapper,
                    Clock.fixed(Instant.parse("2026-03-18T04:00:00Z"), ZoneId.of("Asia/Kolkata"))
            );

            ZerodhaTradebookService.CoverageSummary coverage = reader.coverageSummary();
            List<AnalysisModels.TradeSnapshot> trades =
                    reader.getImportedTrades(LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30));

            assertTrue(coverage.hasImports());
            assertEquals(2, coverage.importedTrades());
            assertEquals(2, trades.size());

            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertTrue(messages.stream().anyMatch(message -> message.contains("Loaded Zerodha tradebook store")));
            assertTrue(messages.stream().anyMatch(message -> message.contains("Computed Zerodha tradebook coverage")));
            assertTrue(messages.stream().anyMatch(message -> message.contains("Loaded Zerodha imported trades")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
