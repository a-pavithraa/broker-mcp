package com.broker.gateway.icici;

import com.broker.config.BreezeConfig;
import com.broker.exception.BrokerApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
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
    private final BreezeSessionManager sessionManager;
    private final BreezeChecksumGenerator checksumGenerator;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public BreezeApiClient(
            BreezeConfig breezeConfig,
            BreezeSessionManager sessionManager,
            BreezeChecksumGenerator checksumGenerator,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder) {
        this.sessionManager = sessionManager;
        this.checksumGenerator = checksumGenerator;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.clone()
                .baseUrl(breezeConfig.baseUrl())
                .build();
    }

    public JsonNode get(String endpoint) {
        return get(endpoint, Map.of());
    }

    public JsonNode get(String endpoint, Map<String, String> params) {
        return get(endpoint, (Object) params);
    }

    public JsonNode get(String endpoint, Object body) {
        String jsonBody = (body instanceof Map<?, ?> m && m.isEmpty()) ? "{}" : toJson(body);
        return executeRequest(() -> executeAuthenticatedWithRetry(HttpMethod.GET, endpoint, jsonBody));
    }

    public JsonNode post(String endpoint, Object body) {
        String jsonBody = toJson(body);
        return executeRequest(() -> executeAuthenticatedWithRetry(HttpMethod.POST, endpoint, jsonBody));
    }

    public JsonNode put(String endpoint, Object body) {
        String jsonBody = toJson(body);
        return executeRequest(() -> executeAuthenticatedWithRetry(HttpMethod.PUT, endpoint, jsonBody));
    }

    public JsonNode delete(String endpoint, Object body) {
        String jsonBody = toJson(body);
        return executeRequest(() -> executeAuthenticatedWithRetry(HttpMethod.DELETE, endpoint, jsonBody));
    }

    public record SessionDetails(String userId, String sessionKey) {}

    public SessionDetails generateSession(String apiKey, String apiSession) {
        String formBody = "AppKey=" + java.net.URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                + "&SessionToken=" + java.net.URLEncoder.encode(apiSession, StandardCharsets.UTF_8);
        JsonNode response = executeRequest(() -> executeUnauthenticatedWithRetry(
                HttpMethod.GET,
                "/customerdetails",
                MediaType.APPLICATION_FORM_URLENCODED,
                formBody));

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

    private JsonNode executeRequest(RequestExecutor requestExecutor) {
        requestLock.lock();
        try {
            long elapsed = System.currentTimeMillis() - lastRequestTime;
            if (elapsed < MIN_REQUEST_GAP_MS) {
                Thread.sleep(MIN_REQUEST_GAP_MS - elapsed);
            }
            JsonNode result = requestExecutor.execute();
            lastRequestTime = System.currentTimeMillis();
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerApiException("Request interrupted", e);
        } finally {
            requestLock.unlock();
        }
    }

    private JsonNode executeAuthenticatedWithRetry(HttpMethod method, String endpoint, String jsonBody) {
        String timestamp = checksumGenerator.generateTimestamp();
        String checksum = checksumGenerator.generateChecksum(timestamp, jsonBody, sessionManager.getSecretKey());
        return executeWithRetry(method, endpoint, MediaType.APPLICATION_JSON, jsonBody, Map.of(
                HEADER_APP_KEY, sessionManager.getApiKey(),
                HEADER_SESSION_TOKEN, sessionManager.getBase64SessionToken(),
                HEADER_TIMESTAMP, timestamp,
                HEADER_CHECKSUM, "token " + checksum
        ));
    }

    private JsonNode executeUnauthenticatedWithRetry(
            HttpMethod method,
            String endpoint,
            MediaType contentType,
            String body) {
        return executeWithRetry(method, endpoint, contentType, body, Map.of());
    }

    private JsonNode executeWithRetry(
            HttpMethod method,
            String endpoint,
            MediaType contentType,
            String body,
            Map<String, String> headers) {
        int attempt = 0;
        while (true) {
            try {
                RestClient.RequestBodySpec request = restClient.method(method)
                        .uri(normalizeEndpoint(endpoint))
                        .contentType(contentType);
                headers.forEach(request::header);
                if (body != null) {
                    request.body(body);
                }

                String responseBody = request.retrieve().body(String.class);
                return objectMapper.readTree(responseBody);
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 503 && attempt < MAX_RETRIES) {
                    attempt++;
                    log.warn("Retrying Breeze request after HTTP 503 for {} attempt {}/{}",
                            normalizeEndpoint(endpoint), attempt, MAX_RETRIES);
                    sleepBeforeRetry(attempt);
                    continue;
                }
                log.error("Breeze request failed status={} endpoint={} body={}",
                        e.getStatusCode().value(),
                        normalizeEndpoint(endpoint),
                        truncate(e.getResponseBodyAsString()));
                throw new BrokerApiException("Breeze API error: " + e.getResponseBodyAsString(), e.getStatusCode().value());
            } catch (JacksonException e) {
                throw new BrokerApiException("Failed to parse Breeze API response: " + e.getMessage(), e);
            } catch (RestClientException e) {
                throw new BrokerApiException("Failed to communicate with Breeze API: " + e.getMessage(), e);
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

    private String normalizeEndpoint(String endpoint) {
        return endpoint.startsWith("/") ? endpoint : "/" + endpoint;
    }

    private String truncate(String body) {
        return body.substring(0, Math.min(200, body.length()));
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(RETRY_DELAY_MS * attempt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BrokerApiException("Request interrupted", ex);
        }
    }

    @FunctionalInterface
    private interface RequestExecutor {
        JsonNode execute() throws InterruptedException;
    }
}
