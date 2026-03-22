package com.broker.reference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorporateActionServiceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final StockMetadataService stockMetadataService =
            new StockMetadataService(new DefaultResourceLoader(), objectMapper, "classpath:stock-universe.csv");

    @Test
    void listActions_shouldMergeSeedAndExternalStore(@TempDir Path tempDir) throws Exception {
        Path storePath = tempDir.resolve("corporate-actions.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(storePath.toFile(), List.of(
                java.util.Map.of(
                        "stockCode", "INFY",
                        "exDate", "2025-01-15",
                        "quantityMultiplier", 1.0,
                        "type", "ALLOTMENT",
                        "notes", "Employee allotment"
                )
        ));

        CorporateActionService service = new CorporateActionService(
                new DefaultResourceLoader(),
                objectMapper,
                stockMetadataService,
                "classpath:stock-corporate-actions.json",
                storePath.toString(),
                fixedClock()
        );

        List<CorporateActionService.CorporateAction> actions = service.listActions();

        assertTrue(actions.stream().anyMatch(action -> "ADAPOW".equals(action.stockCode())));
        assertTrue(actions.stream().anyMatch(action -> "CDSL".equals(action.stockCode())));
        assertTrue(actions.stream().anyMatch(action -> "LGELEC".equals(action.stockCode())));
        assertTrue(actions.stream().anyMatch(action -> "TATCAP".equals(action.stockCode())));
        assertTrue(actions.stream().anyMatch(action ->
                "INFTEC".equals(action.stockCode())
                        && "ALLOTMENT".equals(action.type())
                        && LocalDate.of(2025, 1, 15).equals(action.exDate())));
    }

    @Test
    void upsertAction_shouldPersistAndNormalizeEntries(@TempDir Path tempDir) {
        Path storePath = tempDir.resolve("corporate-actions.json");
        CorporateActionService service = new CorporateActionService(
                new DefaultResourceLoader(),
                objectMapper,
                stockMetadataService,
                "classpath:stock-corporate-actions.json",
                storePath.toString(),
                fixedClock()
        );

        CorporateActionService.CorporateAction saved = service.upsertAction(
                "TATACAP",
                "allotment",
                LocalDate.of(2025, 10, 10),
                1.0,
                "https://example.test/tata-capital",
                "IPO allotment"
        );

        assertEquals("TATCAP", saved.stockCode());
        assertEquals("ALLOTMENT", saved.type());
        assertTrue(storePath.toFile().isFile());
        assertTrue(service.listActions().stream().anyMatch(action ->
                "TATCAP".equals(action.stockCode())
                        && "ALLOTMENT".equals(action.type())
                        && LocalDate.of(2025, 10, 10).equals(action.exDate())));
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-03-20T05:30:00Z"), ZoneId.of("Asia/Kolkata"));
    }
}
