package com.broker.gateway.zerodha;

import com.broker.exception.BrokerApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

@Service
@ConditionalOnProperty(name = "zerodha.enabled", havingValue = "true")
public class ZerodhaApiClient {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaApiClient.class);
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 250;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ZerodhaSessionManager sessionManager;
    private final String baseUrl;

    public ZerodhaApiClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            ZerodhaSessionManager sessionManager,
            @org.springframework.beans.factory.annotation.Value("${zerodha.base-url:https://api.kite.trade}") String baseUrl) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.sessionManager = sessionManager;
        this.baseUrl = baseUrl;
    }

    public JsonNode get(String path) {
        return get(path, Map.of());
    }

    public JsonNode get(String path, Map<String, ?> params) {
        HttpRequest request = requestBuilder(path, params)
                .GET()
                .build();
        return send(request);
    }

    public JsonNode postForm(String path, Map<String, String> body) {
        HttpRequest request = requestBuilder(path, Map.of())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(body)))
                .build();
        return send(request);
    }

    public JsonNode postJson(String path, Object body) {
        HttpRequest request = requestBuilder(path, Map.of())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body)))
                .build();
        return send(request);
    }

    private HttpRequest.Builder requestBuilder(String path, Map<String, ?> params) {
        return HttpRequest.newBuilder()
                .uri(URI.create(buildUrl(path, params)))
                .header("X-Kite-Version", "3")
                .header("Authorization", "token " + sessionManager.getApiKey() + ":" + sessionManager.getAccessToken())
                .timeout(Duration.ofSeconds(30));
    }

    private String buildUrl(String path, Map<String, ?> params) {
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        if (params == null || params.isEmpty()) {
            return baseUrl + normalizedPath;
        }

        List<String> encoded = new ArrayList<>();
        for (Map.Entry<String, ?> entry : params.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (entry.getValue() instanceof Iterable<?> iterable) {
                for (Object value : iterable) {
                    if (value != null) {
                        encoded.add(encode(entry.getKey(), String.valueOf(value)));
                    }
                }
            } else {
                encoded.add(encode(entry.getKey(), String.valueOf(entry.getValue())));
            }
        }
        return encoded.isEmpty() ? baseUrl + normalizedPath : baseUrl + normalizedPath + "?" + String.join("&", encoded);
    }

    private JsonNode send(HttpRequest request) {
        int attempt = 0;
        try {
            while (true) {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 503 && attempt < MAX_RETRIES) {
                    attempt++;
                    log.warn("Retrying Zerodha request after HTTP 503 for {} attempt {}/{}",
                            request.uri(), attempt, MAX_RETRIES);
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                    continue;
                }
                if (response.statusCode() >= 400) {
                    throw new BrokerApiException("Zerodha API error: HTTP " + response.statusCode() + " " + response.body(), response.statusCode());
                }
                JsonNode parsed = objectMapper.readTree(response.body());
                if ("error".equalsIgnoreCase(parsed.path("status").asText())) {
                    throw new BrokerApiException(parsed.path("message").asText("Zerodha API request failed"));
                }
                return parsed;
            }
        } catch (IOException e) {
            throw new BrokerApiException("Zerodha API request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerApiException("Zerodha API request interrupted", e);
        }
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException e) {
            throw new BrokerApiException("Failed to serialize Zerodha request body", e);
        }
    }

    private String formEncode(Map<String, String> body) {
        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, String> entry : body.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            joiner.add(encode(entry.getKey(), entry.getValue()));
        }
        return joiner.toString();
    }

    private String encode(String key, String value) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
