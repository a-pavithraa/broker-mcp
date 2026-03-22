package com.broker.tools;

import com.broker.reference.CorporateActionService;
import com.broker.reference.StockMetadataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CorporateActionWriteToolsTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final StockMetadataService stockMetadataService =
            new StockMetadataService(new DefaultResourceLoader(), objectMapper, "classpath:stock-universe.csv");

    @Test
    void upsertCorporateAction_shouldPersistReviewedEntry(@TempDir Path tempDir) throws Exception {
        CorporateActionService service = new CorporateActionService(
                new DefaultResourceLoader(),
                objectMapper,
                stockMetadataService,
                "classpath:stock-corporate-actions.json",
                tempDir.resolve("corporate-actions.json").toString(),
                fixedClock()
        );
        CorporateActionWriteTools tools = new CorporateActionWriteTools(service, objectMapper);

        Map<String, Object> result = objectMapper.readValue(tools.upsertCorporateAction(
                "TATACAP",
                "ALLOTMENT",
                "2025-10-10",
                1.0,
                "https://example.test/tata-capital",
                "IPO allotment"
        ), Map.class);

        assertEquals("TATCAP", result.get("stockCode"));
        assertEquals("ALLOTMENT", result.get("type"));
        assertEquals("2025-10-10", result.get("exDate"));
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-03-20T05:30:00Z"), ZoneId.of("Asia/Kolkata"));
    }
}
