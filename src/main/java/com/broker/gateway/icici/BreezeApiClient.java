package com.broker.gateway.icici;

import com.broker.config.BreezeConfig;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

@Service
@ConditionalOnProperty(name = "breeze.enabled", havingValue = "true")
public class BreezeApiClient {

    private static final Logger log = LoggerFactory.getLogger(BreezeApiClient.class);
    private static final String HEADER_APP_KEY = "X-AppKey";
    private static final String HEADER_SESSION_TOKEN = "X-SessionToken";
    private static final String HEADER_TIMESTAMP = "X-Timestamp";
    private static final String HEADER_CHECKSUM = "X-Checksum";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 500;
    private static final long MIN_REQUEST_GAP_MS = 500;

    private final ReentrantLock requestLock = new ReentrantLock();
    private volatile long lastRequestTime = 0;
    private final BreezeConfig breezeConfig;
    private final BreezeSessionManager sessionManager;
    private final BreezeChecksumGenerator checksumGenerator;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public BreezeApiClient(
            BreezeConfig breezeConfig,
            BreezeSessionManager sessionManager,
            BreezeChecksumGenerator checksumGenerator,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.breezeConfig = breezeConfig;
        this.sessionManager = sessionManager;
        this.checksumGenerator = checksumGenerator;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public JsonNode get(String endpoint) {
        return get(endpoint, Map.of());
    }

    public JsonNode get(String endpoint, Map<String, String> params) {
        return get(endpoint, (Object) params);
    }

    public JsonNode get(String endpoint, Object body) {
        String url = buildUrl(endpoint);
        String jsonBody = (body instanceof Map<?, ?> m && m.isEmpty()) ? "{}" : toJson(body);
        HttpRequest request = authenticatedRequestBuilder(url, jsonBody)
                .method("GET", HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(60))
                .build();

        return executeRequest(request);
    }

    public JsonNode post(String endpoint, Object body) {
        String url = buildUrl(endpoint);
        String jsonBody = toJson(body);
        HttpRequest request = authenticatedRequestBuilder(url, jsonBody)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(60))
                .build();

        return executeRequest(request);
    }

    public JsonNode put(String endpoint, Object body) {
        String url = buildUrl(endpoint);
        String jsonBody = toJson(body);
        HttpRequest request = authenticatedRequestBuilder(url, jsonBody)
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(60))
                .build();

        return executeRequest(request);
    }

    public JsonNode delete(String endpoint, Object body) {
        String url = buildUrl(endpoint);
        String jsonBody = toJson(body);
        HttpRequest request = authenticatedRequestBuilder(url, jsonBody)
                .method("DELETE", HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(60))
                .build();

        return executeRequest(request);
    }

    public record SessionDetails(String userId, String sessionKey) {}

    public SessionDetails generateSession(String apiKey, String apiSession) {
        String url = breezeConfig.baseUrl() + "/customerdetails";
        String formBody = "AppKey=" + java.net.URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                + "&SessionToken=" + java.net.URLEncoder.encode(apiSession, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method("GET", HttpRequest.BodyPublishers.ofString(formBody))
                .timeout(Duration.ofSeconds(30))
                .build();

        JsonNode response = executeRequest(request);

        JsonNode success = response.path("Success");
        if (success.isMissingNode() || !success.has("session_token")) {
            String error = response.has("Error") ? response.get("Error").asText() : response.toString();
            throw new BrokerApiException("Session generation failed: " + error);
        }

        String base64Token = success.get("session_token").asText();
        String decoded = new String(
                java.util.Base64.getDecoder().decode(base64Token), StandardCharsets.UTF_8);
        String[] parts = decoded.split(":", 2);
        if (parts.length != 2) {
            throw new BrokerApiException(
                    "Unexpected session_token format — expected base64(userId:sessionKey)");
        }

        return new SessionDetails(parts[0], parts[1]);
    }

    private String buildUrl(String endpoint) {
        String baseUrl = breezeConfig.baseUrl();
        if (endpoint.startsWith("/")) {
            return baseUrl + endpoint;
        }
        return baseUrl + "/" + endpoint;
    }

    private HttpRequest.Builder authenticatedRequestBuilder(String url, String jsonBody) {
        String timestamp = checksumGenerator.generateTimestamp();
        String checksum = checksumGenerator.generateChecksum(timestamp, jsonBody, sessionManager.getSecretKey());
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header(HEADER_APP_KEY, sessionManager.getApiKey())
                .header(HEADER_SESSION_TOKEN, sessionManager.getBase64SessionToken())
                .header(HEADER_TIMESTAMP, timestamp)
                .header(HEADER_CHECKSUM, "token " + checksum);
    }

    private JsonNode executeRequest(HttpRequest request) {
        requestLock.lock();
        try {
            long elapsed = System.currentTimeMillis() - lastRequestTime;
            if (elapsed < MIN_REQUEST_GAP_MS) {
                Thread.sleep(MIN_REQUEST_GAP_MS - elapsed);
            }
            JsonNode result = executeWithRetry(request);
            lastRequestTime = System.currentTimeMillis();
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerApiException("Request interrupted", e);
        } finally {
            requestLock.unlock();
        }
    }

    private JsonNode executeWithRetry(HttpRequest request) {
        int attempt = 0;
        while (true) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 503 && attempt < MAX_RETRIES) {
                    attempt++;
                    log.warn("Retrying Breeze request after HTTP 503 for {} attempt {}/{}",
                            request.uri(), attempt, MAX_RETRIES);
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                    continue;
                }

                if (response.statusCode() >= 400) {
                    log.error("Breeze request failed status={} url={} body={}",
                            response.statusCode(),
                            request.uri(),
                            response.body().substring(0, Math.min(200, response.body().length())));
                    throw new BrokerApiException(
                            "Breeze API error: " + response.body(),
                            response.statusCode()
                    );
                }

                return objectMapper.readTree(response.body());
            } catch (JacksonException e) {
                throw new BrokerApiException("Failed to parse Breeze API response: " + e.getMessage(), e);
            } catch (IOException e) {
                throw new BrokerApiException("Failed to communicate with Breeze API: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BrokerApiException("Request interrupted", e);
            }
        }
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException e) {
            throw new BrokerApiException("Failed to serialize request body", e);
        }
    }
}
