package com.broker.gateway.zerodha;

import com.broker.exception.BrokerApiException;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZerodhaApiClientTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void get_shouldSendQueryParamsAndAuthorizationHeader() {
        wireMock.stubFor(get(urlPathEqualTo("/quote"))
                .withQueryParam("i", equalTo("NSE:INFY"))
                .withHeader("X-Kite-Version", equalTo("3"))
                .withHeader("Authorization", equalTo("token api_key:access_token"))
                .willReturn(okJson("""
                        {"status":"success","data":{"ok":true}}
                        """)));

        ZerodhaSessionManager sessionManager = new ZerodhaSessionManager("ZERODHA_USER");
        sessionManager.setSession("api_key", "access_token");
        ZerodhaApiClient client = new ZerodhaApiClient(RestClient.builder(), objectMapper, sessionManager, wireMock.baseUrl());

        assertTrue(client.get("/quote", Map.of("i", "NSE:INFY")).path("data").path("ok").asBoolean());
    }

    @Test
    void get_shouldRetryTransient503Responses() {
        wireMock.stubFor(get(urlPathEqualTo("/quote"))
                .inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse()
                        .withStatus(503)
                        .withBody("{\"status\":\"error\",\"message\":\"busy\"}"))
                .willSetStateTo("second"));
        wireMock.stubFor(get(urlPathEqualTo("/quote"))
                .inScenario("retry")
                .whenScenarioStateIs("second")
                .willReturn(okJson("""
                        {"status":"success","data":{"ok":true}}
                        """)));

        ZerodhaSessionManager sessionManager = new ZerodhaSessionManager("ZERODHA_USER");
        sessionManager.setSession("api_key", "access_token");
        ZerodhaApiClient client = new ZerodhaApiClient(RestClient.builder(), objectMapper, sessionManager, wireMock.baseUrl());

        assertTrue(client.get("/quote").path("data").path("ok").asBoolean());
        wireMock.verify(2, getRequestedFor(urlPathEqualTo("/quote")));
    }

    @Test
    void get_shouldThrowAfterRetryBudgetIsExhausted() {
        wireMock.stubFor(get(urlPathEqualTo("/quote"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withBody("{\"status\":\"error\",\"message\":\"still busy\"}")));

        ZerodhaSessionManager sessionManager = new ZerodhaSessionManager("ZERODHA_USER");
        sessionManager.setSession("api_key", "access_token");
        ZerodhaApiClient client = new ZerodhaApiClient(RestClient.builder(), objectMapper, sessionManager, wireMock.baseUrl());

        BrokerApiException exception = assertThrows(BrokerApiException.class, () -> client.get("/quote"));

        assertEquals(503, exception.getStatusCode());
        wireMock.verify(3, getRequestedFor(urlPathEqualTo("/quote")));
    }

    @Test
    void sessionManager_shouldUseConfiguredDefaultUserIdWhenSessionUserIsOmitted() {
        ZerodhaSessionManager sessionManager = new ZerodhaSessionManager("ZERODHA_USER");

        sessionManager.setSession("api_key", "access_token");

        assertEquals("ZERODHA_USER", sessionManager.getUserId());
    }
}
