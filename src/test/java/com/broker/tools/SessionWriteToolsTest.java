package com.broker.tools;

import com.broker.config.BreezeConfig;
import com.broker.gateway.icici.BreezeApiClient;
import com.broker.gateway.icici.BreezeSessionManager;
import com.broker.gateway.icici.BreezeChecksumGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionWriteToolsTest {

    private SessionWriteTools sessionWriteTools;

    @BeforeEach
    void setUp() {
        BreezeSessionManager sessionManager = new BreezeSessionManager();
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
        BreezeApiClient apiClient = new BreezeApiClient(
                new BreezeConfig(null, null), sessionManager, new BreezeChecksumGenerator(),
                JsonMapper.builder().build(), httpClient);
        sessionWriteTools = new SessionWriteTools(sessionManager, apiClient);
    }

    @Test
    void setSession_shouldRejectEmptyApiKey() {
        String result = sessionWriteTools.setSession("", "secret", "token");

        assertTrue(result.contains("Error"));
        assertTrue(result.contains("apiKey"));
    }

    @Test
    void setSession_shouldRejectEmptySecret() {
        String result = sessionWriteTools.setSession("apiKey", "", "token");

        assertTrue(result.contains("Error"));
        assertTrue(result.contains("apiSecret"));
    }

    @Test
    void setSession_shouldRejectEmptyApiSession() {
        String result = sessionWriteTools.setSession("apiKey", "secret", "");

        assertTrue(result.contains("Error"));
        assertTrue(result.contains("apiSession"));
    }
}
