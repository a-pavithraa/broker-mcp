package com.broker.tools;

import com.broker.service.CorporateActionService;
import com.broker.service.StockMetadataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CorporateActionToolsTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final StockMetadataService stockMetadataService =
            new StockMetadataService(new DefaultResourceLoader(), objectMapper, "classpath:stock-universe.csv");

    @Test
    void listCorporateActions_shouldReturnStructuredJson(@TempDir Path tempDir) throws Exception {
        CorporateActionService service = new CorporateActionService(
                new DefaultResourceLoader(),
                objectMapper,
                stockMetadataService,
                "classpath:stock-corporate-actions.json",
                tempDir.resolve("corporate-actions.json").toString(),
                fixedClock()
        );
        CorporateActionTools tools = new CorporateActionTools(service, objectMapper);

        List<Map<String, Object>> result = objectMapper.readValue(tools.listCorporateActions(), List.class);

        assertTrue(result.size() >= 4);
        assertTrue(result.stream().anyMatch(item -> "ADAPOW".equals(item.get("stockCode"))));
        assertTrue(result.stream().anyMatch(item -> "LGELEC".equals(item.get("stockCode"))));
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-03-20T05:30:00Z"), ZoneId.of("Asia/Kolkata"));
    }
}
