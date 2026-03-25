package com.broker.gateway.icici;

import com.broker.config.BreezeConfig;
import com.broker.exception.SessionNotInitializedException;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreezeApiClientTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private BreezeApiClient client;
    private BreezeSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        BreezeConfig config = new BreezeConfig(wireMock.baseUrl(), null);

        sessionManager = new BreezeSessionManager();
        BreezeChecksumGenerator checksumGenerator = new BreezeChecksumGenerator();
        JsonMapper objectMapper = JsonMapper.builder().build();

        client = new BreezeApiClient(config, sessionManager, checksumGenerator, objectMapper, RestClient.builder());
    }

    @Test
    void get_shouldThrowExceptionWithoutSession() {
        assertThrows(SessionNotInitializedException.class, () -> client.get("/funds"));
    }

    @Test
    void post_shouldThrowExceptionWithoutSession() {
        assertThrows(SessionNotInitializedException.class, () -> client.post("/funds", java.util.Map.of("key", "value")));
    }

    @Test
    void put_shouldThrowExceptionWithoutSession() {
        assertThrows(SessionNotInitializedException.class, () -> client.put("/order", java.util.Map.of("key", "value")));
    }

    @Test
    void delete_shouldThrowExceptionWithoutSession() {
        assertThrows(SessionNotInitializedException.class, () -> client.delete("/order", java.util.Map.of("key", "value")));
    }

    @Test
    void get_shouldSendAuthenticatedHeadersAndRetry503() {
        sessionManager.setSession("api_key", "secret_key", "session_token", "ICICI_USER");

        wireMock.stubFor(any(urlEqualTo("/funds"))
                .inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503).withBody("{\"Error\":\"busy\"}"))
                .willSetStateTo("second"));
        wireMock.stubFor(any(urlEqualTo("/funds"))
                .inScenario("retry")
                .whenScenarioStateIs("second")
                .withHeader("X-AppKey", equalTo("api_key"))
                .withHeader("X-SessionToken", equalTo(sessionManager.getBase64SessionToken()))
                .withHeader("X-Timestamp", matching(".+"))
                .withHeader("X-Checksum", matching("token [0-9a-f]+"))
                .willReturn(okJson("""
                        {"Success":{"ok":true}}
                        """)));

        assertTrue(client.get("/funds").path("Success").path("ok").asBoolean());
        wireMock.verify(2, anyRequestedFor(urlEqualTo("/funds")));
    }

    @Test
    void generateSession_shouldDecodeReturnedToken() {
        wireMock.stubFor(any(urlEqualTo("/customerdetails"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .willReturn(okJson("""
                        {"Success":{"session_token":"VVNFUjEyMzpTRVNTSU9OS0VZ"}}
                        """)));

        BreezeApiClient.SessionDetails details = client.generateSession("api", "session");

        assertEquals("USER123", details.userId());
        assertEquals("SESSIONKEY", details.sessionKey());
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
