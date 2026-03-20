package com.broker.tools;

import com.broker.service.ZerodhaSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZerodhaSessionWriteToolsTest {

    private ObjectMapper objectMapper;
    private ZerodhaSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        sessionManager = new ZerodhaSessionManager();
    }

    @Test
    void setSession_shouldRejectMissingAccessToken() throws Exception {
        ZerodhaSessionWriteTools tools = new ZerodhaSessionWriteTools(
                sessionManager,
                objectMapper,
                key -> "ZERODHA_API_KEY".equals(key) ? "kite1234" : null
        );

        Map<String, Object> result = objectMapper.readValue(tools.setSession(""), Map.class);

        assertEquals("ERROR", result.get("status"));
        assertTrue(String.valueOf(result.get("message")).contains("accessToken"));
    }

    @Test
    void setSession_shouldRejectWhenApiKeyIsUnavailable() throws Exception {
        ZerodhaSessionWriteTools tools = new ZerodhaSessionWriteTools(
                sessionManager,
                objectMapper,
                key -> null
        );

        Map<String, Object> result = objectMapper.readValue(tools.setSession("access-token"), Map.class);

        assertEquals("ERROR", result.get("status"));
        assertTrue(String.valueOf(result.get("message")).contains("ZERODHA_API_KEY"));
    }

    @Test
    void setSession_shouldUpdateSessionManager() throws Exception {
        ZerodhaSessionWriteTools tools = new ZerodhaSessionWriteTools(
                sessionManager,
                objectMapper,
                key -> switch (key) {
                    case "ZERODHA_API_KEY" -> "kite1234";
                    case "ZERODHA_USER_ID" -> "ZU1234";
                    default -> null;
                }
        );

        Map<String, Object> result = objectMapper.readValue(tools.setSession("access-token"), Map.class);

        assertEquals("OK", result.get("status"));
        assertEquals("ZU1234", result.get("user_id"));
        assertTrue(sessionManager.hasActiveSession());
        assertEquals("kite1234", sessionManager.getApiKey());
        assertEquals("access-token", sessionManager.getAccessToken());
    }
}
