package com.broker.service;

import com.broker.config.BreezeConfig;
import com.broker.exception.SessionNotInitializedException;
import com.broker.util.BreezeChecksumGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class BreezeApiClientTest {

    private BreezeApiClient client;
    private BreezeSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        BreezeConfig config = new BreezeConfig(
               "https://api.icicidirect.com/breezeapi/api/v1", null);

        sessionManager = new BreezeSessionManager();
        BreezeChecksumGenerator checksumGenerator = new BreezeChecksumGenerator();
        JsonMapper objectMapper = JsonMapper.builder().build();
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();

        client = new BreezeApiClient(config, sessionManager, checksumGenerator, objectMapper, httpClient);
    }

    @Test
    void get_shouldThrowExceptionWithoutSession() {
        assertThrows(SessionNotInitializedException.class, () -> {
            client.get("/funds");
        });
    }

    @Test
    void post_shouldThrowExceptionWithoutSession() {
        // post() calls toJson() first which doesn't check session,
        // but when building the request it will check session
        assertThrows(SessionNotInitializedException.class, () -> {
            client.post("/funds", java.util.Map.of("key", "value"));
        });
    }

    @Test
    void put_shouldThrowExceptionWithoutSession() {
        assertThrows(SessionNotInitializedException.class, () -> {
            client.put("/order", java.util.Map.of("key", "value"));
        });
    }

    @Test
    void delete_shouldThrowExceptionWithoutSession() {
        assertThrows(SessionNotInitializedException.class, () -> {
            client.delete("/order", java.util.Map.of("key", "value"));
        });
    }

    @Test
    void sessionManager_shouldStoreCredentials() {
        sessionManager.setSession("api_key", "secret_key", "session_token");

        assertTrue(sessionManager.hasActiveSession());
        assertEquals("api_key", sessionManager.getApiKey());
        assertEquals("secret_key", sessionManager.getSecretKey());
        assertEquals("session_token", sessionManager.getSessionToken());
    }

    @Test
    void sessionManager_shouldMaskApiKey() {
        sessionManager.setSession("my_long_api_key", "secret", "token");

        String masked = sessionManager.getMaskedApiKey();

        assertEquals("my_l****", masked);
    }

    @Test
    void sessionManager_shouldUseConfiguredDefaultUserIdWhenSessionUserIsOmitted() {
        BreezeSessionManager configuredSessionManager = new BreezeSessionManager("ICICI_USER");

        configuredSessionManager.setSession("api_key", "secret_key", "session_token");

        assertEquals("ICICI_USER", configuredSessionManager.getUserId());
    }

    @Test
    void sessionManager_shouldClearSession() {
        sessionManager.setSession("api_key", "secret_key", "session_token");
        assertTrue(sessionManager.hasActiveSession());

        sessionManager.clearSession();

        assertFalse(sessionManager.hasActiveSession());
    }
}
