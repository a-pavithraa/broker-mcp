package com.broker.gateway;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayJsonHelperTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void textAndNumber_shouldPreserveGatewayParsingSemantics() {
        JsonNode node = objectMapper.valueToTree(Map.of(
                "primary", "",
                "fallback", "1,234.56",
                "invalid", "n/a",
                "text_value", "INFY"
        ));

        assertEquals("INFY", GatewayJsonHelper.text(node, "text_value"));
        assertEquals("NSE", GatewayJsonHelper.text(node, "missing", "NSE"));
        assertEquals(1234.56, GatewayJsonHelper.number(node, "primary", "fallback"));
        assertEquals(0.0, GatewayJsonHelper.number(node, "invalid"));
        assertEquals("fallback", GatewayJsonHelper.firstNonBlank("", "fallback", "later"));
    }
}
