package com.broker.config;

import com.broker.model.AnalysisModels.TradeSnapshot;
import com.broker.reference.StockMetadataService;
import com.broker.gateway.zerodha.ZerodhaTradebookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ZerodhaTradebookStartupImporterTest {

    private ObjectMapper objectMapper;
    private StockMetadataService stockMetadataService;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        stockMetadataService = new StockMetadataService(new DefaultResourceLoader(), objectMapper, "classpath:stock-universe.csv");
    }

    @Test
    void startupImportShouldLoadAllCsvsFromImportRoot(@TempDir Path tempDir) throws Exception {
        Path importRoot = Files.createDirectories(tempDir.resolve("imports"));
        Path storePath = tempDir.resolve("data").resolve("zerodha-tradebook.json");
        Path olderCsv = importRoot.resolve("older.csv");
        Path newerCsv = importRoot.resolve("newer.csv");

        Files.writeString(olderCsv, """
                Trade Date,Trading Symbol,Trade Type,Quantity,Price,Exchange,Segment,Order ID,Trade ID
                01/04/2025,INFY,BUY,5,1500,NSE,EQ,OID1,TID1
                """);
        Files.writeString(newerCsv, """
                Trade Date,Trading Symbol,Trade Type,Quantity,Price,Exchange,Segment,Order ID,Trade ID
                10/05/2025,TCS,BUY,3,3200,NSE,EQ,OID2,TID2
                """);
        Files.setLastModifiedTime(olderCsv, FileTime.from(Instant.parse("2026-03-17T04:00:00Z")));
        Files.setLastModifiedTime(newerCsv, FileTime.from(Instant.parse("2026-03-18T04:00:00Z")));

        ZerodhaTradebookProperties properties = new ZerodhaTradebookProperties(importRoot.toString(), storePath.toString());
        ZerodhaTradebookService service = new ZerodhaTradebookService(
                properties,
                stockMetadataService,
                objectMapper
        );
        ZerodhaTradebookStartupImporter importer = new ZerodhaTradebookStartupImporter(properties, service);

        importer.run(new DefaultApplicationArguments(new String[0]));

        ZerodhaTradebookService.CoverageSummary coverage = service.coverageSummary();
        List<TradeSnapshot> trades = service.getImportedTrades(LocalDate.of(2025, 4, 1), LocalDate.of(2025, 5, 31));

        assertTrue(Files.exists(storePath));
        assertTrue(coverage.hasImports());
        assertEquals(LocalDate.of(2025, 4, 1), coverage.coveredFrom());
        assertEquals(LocalDate.of(2025, 5, 10), coverage.coveredTo());
        assertEquals(2, trades.size());
        assertEquals(List.of("INFTEC", "TCS"), trades.stream().map(TradeSnapshot::stockCode).toList());
    }

    @Test
    void startupImportShouldMergeAllCsvFilesFromImportRoot(@TempDir Path tempDir) throws Exception {
        Path importRoot = Files.createDirectories(tempDir.resolve("imports"));
        Path storePath = tempDir.resolve("data").resolve("zerodha-tradebook.json");
        Path fy2024 = importRoot.resolve("zerodha-tradebook-2024.csv");
        Path fy2025 = importRoot.resolve("zerodha-tradebook-2025.csv");

        Files.writeString(fy2024, """
                Trade Date,Trading Symbol,Trade Type,Quantity,Price,Exchange,Segment,Order ID,Trade ID
                20/03/2024,INFY,BUY,5,1500,NSE,EQ,OID1,TID1
                """);
        Files.writeString(fy2025, """
                Trade Date,Trading Symbol,Trade Type,Quantity,Price,Exchange,Segment,Order ID,Trade ID
                10/05/2025,TCS,BUY,3,3200,NSE,EQ,OID2,TID2
                """);
        Files.setLastModifiedTime(fy2024, FileTime.from(Instant.parse("2026-03-18T04:00:00Z")));
        Files.setLastModifiedTime(fy2025, FileTime.from(Instant.parse("2026-03-19T04:00:00Z")));

        ZerodhaTradebookProperties properties = new ZerodhaTradebookProperties(importRoot.toString(), storePath.toString());
        ZerodhaTradebookService service = new ZerodhaTradebookService(
                properties,
                stockMetadataService,
                objectMapper
        );
        ZerodhaTradebookStartupImporter importer = new ZerodhaTradebookStartupImporter(properties, service);

        importer.run(new DefaultApplicationArguments(new String[0]));

        List<TradeSnapshot> trades = service.getImportedTrades(LocalDate.of(2024, 3, 20), LocalDate.of(2025, 5, 31));

        assertTrue(Files.exists(storePath));
        assertTrue(service.coverageSummary().hasImports());
        assertEquals(2, trades.size());
        assertEquals(List.of("INFTEC", "TCS"), trades.stream().map(TradeSnapshot::stockCode).toList());
    }

    @Test
    void startupImportShouldSkipWhenImportRootHasNoCsv(@TempDir Path tempDir) throws Exception {
        Path importRoot = Files.createDirectories(tempDir.resolve("imports"));
        Path storePath = tempDir.resolve("data").resolve("zerodha-tradebook.json");

        ZerodhaTradebookProperties properties = new ZerodhaTradebookProperties(importRoot.toString(), storePath.toString());
        ZerodhaTradebookService service = new ZerodhaTradebookService(
                properties,
                stockMetadataService,
                objectMapper
        );
        ZerodhaTradebookStartupImporter importer = new ZerodhaTradebookStartupImporter(properties, service);

        importer.run(new DefaultApplicationArguments(new String[0]));

        assertFalse(Files.exists(storePath));
        assertFalse(service.coverageSummary().hasImports());
    }
}
