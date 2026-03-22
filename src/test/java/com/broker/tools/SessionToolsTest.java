package com.broker.tools;

import com.broker.config.TransportModeDetector;
import com.broker.gateway.icici.BreezeSessionManager;
import com.broker.gateway.zerodha.ZerodhaSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionToolsTest {

    private SessionTools sessionTools;
    private BreezeSessionManager sessionManager;
    private ZerodhaSessionManager zerodhaSessionManager;
    private TransportModeDetector stdioDetector;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        stdioDetector = new TransportModeDetector();
        sessionManager = new BreezeSessionManager();
        zerodhaSessionManager = null;
        objectMapper = JsonMapper.builder().build();
        sessionTools = new SessionTools(stdioDetector, sessionManager, zerodhaSessionManager, objectMapper);
    }

    @Test
    void sessionStatus_shouldShowNoActiveSession() throws Exception {
        Map<String, Object> status = objectMapper.readValue(sessionTools.sessionStatus(), Map.class);

        assertEquals(false, status.get("session_active"));
        assertTrue(String.valueOf(status.get("transport_mode")).contains("stdio"));
        assertTrue(((List<?>) status.get("next_steps")).size() > 0);
    }

    @Test
    void sessionStatus_shouldShowMaskedApiKeyWhenActive() throws Exception {
        sessionManager.setSession("abcd1234", "secret", "token", "user");

        Map<String, Object> status = objectMapper.readValue(sessionTools.sessionStatus(), Map.class);

        assertEquals(true, status.get("session_active"));
        assertTrue(String.valueOf(status.get("api_key")).contains("abcd****"));
        Map<String, Object> brokers = (Map<String, Object>) status.get("brokers");
        Map<String, Object> icici = (Map<String, Object>) brokers.get("icici_breeze");
        assertEquals(true, icici.get("active"));
    }

    @Test
    void sessionStatus_shouldIncludeZerodhaBlockWhenConfigured() throws Exception {
        zerodhaSessionManager = new ZerodhaSessionManager();
        zerodhaSessionManager.setSession("kite1234", "access-token", "ZU1234");
        sessionTools = new SessionTools(stdioDetector, sessionManager, zerodhaSessionManager, objectMapper);

        Map<String, Object> status = objectMapper.readValue(sessionTools.sessionStatus(), Map.class);

        assertEquals(true, status.get("session_active"));
        Map<String, Object> brokers = (Map<String, Object>) status.get("brokers");
        Map<String, Object> zerodha = (Map<String, Object>) brokers.get("zerodha_kite");
        assertEquals(true, zerodha.get("active"));
        assertEquals("ZU1234", zerodha.get("user_id"));
        assertTrue(String.valueOf(zerodha.get("api_key")).contains("kite****"));
        assertTrue(zerodha.containsKey("expires_at"));
    }
}
