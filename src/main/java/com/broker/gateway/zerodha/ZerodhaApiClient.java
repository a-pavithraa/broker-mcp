package com.broker.gateway.zerodha;

import com.broker.exception.BrokerApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.function.Consumer;

@Service
@ConditionalOnProperty(name = "zerodha.enabled", havingValue = "true")
public class ZerodhaApiClient {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaApiClient.class);
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 250;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ZerodhaSessionManager sessionManager;

    public ZerodhaApiClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            ZerodhaSessionManager sessionManager,
            @Value("${zerodha.base-url:https://api.kite.trade}") String baseUrl) {
        this.restClient = restClientBuilder.clone()
                .baseUrl(baseUrl)
                .build();
        this.objectMapper = objectMapper;
        this.sessionManager = sessionManager;
    }

    public JsonNode get(String path) {
        return get(path, Map.of());
    }

    public JsonNode get(String path, Map<String, ?> params) {
        return send(HttpMethod.GET, path, builder -> appendQueryParams(builder, params), null, null);
    }

    public JsonNode postForm(String path, Map<String, String> body) {
        return send(HttpMethod.POST, path, builder -> {}, MediaType.APPLICATION_FORM_URLENCODED, formBody(body));
    }

    public JsonNode postJson(String path, Object body) {
        return send(HttpMethod.POST, path, builder -> {}, MediaType.APPLICATION_JSON, body);
    }

    private JsonNode send(
            HttpMethod method,
            String path,
            Consumer<UriBuilder> queryWriter,
            MediaType contentType,
            Object body) {
        int attempt = 0;
        while (true) {
            try {
                RestClient.RequestBodySpec request = restClient.method(method)
                        .uri(uriBuilder -> {
                            UriBuilder builder = uriBuilder.path(normalizePath(path));
                            queryWriter.accept(builder);
                            return builder.build();
                        })
                        .header("X-Kite-Version", "3")
                        .header("Authorization",
                                "token " + sessionManager.getApiKey() + ":" + sessionManager.getAccessToken());
                if (contentType != null) {
                    request.contentType(contentType);
                }
                if (body != null) {
                    request.body(body);
                }

                String responseBody = request.retrieve().body(String.class);
                JsonNode parsed = objectMapper.readTree(responseBody);
                if ("error".equalsIgnoreCase(parsed.path("status").asText())) {
                    throw new BrokerApiException(parsed.path("message").asText("Zerodha API request failed"));
                }
                return parsed;
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode().value() == 503 && attempt < MAX_RETRIES) {
                    attempt++;
                    log.warn("Retrying Zerodha request after HTTP 503 for {} attempt {}/{}",
                            normalizePath(path), attempt, MAX_RETRIES);
                    sleepBeforeRetry(attempt);
                    continue;
                }
                throw new BrokerApiException(
                        "Zerodha API error: HTTP " + ex.getStatusCode().value() + " " + ex.getResponseBodyAsString(),
                        ex.getStatusCode().value());
            } catch (RestClientException ex) {
                throw new BrokerApiException("Zerodha API request failed", ex);
            } catch (JacksonException ex) {
                throw new BrokerApiException("Failed to parse Zerodha API response", ex);
            }
        }
    }

    private void appendQueryParams(UriBuilder builder, Map<String, ?> params) {
        if (params == null || params.isEmpty()) {
            return;
        }
        params.forEach((key, value) -> addQueryValue(builder, key, value));
    }

    private void addQueryValue(UriBuilder builder, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null) {
                    builder.queryParam(key, String.valueOf(item));
                }
            }
            return;
        }
        builder.queryParam(key, String.valueOf(value));
    }

    private MultiValueMap<String, String> formBody(Map<String, String> body) {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        body.forEach((key, value) -> {
            if (value != null) {
                form.add(key, value);
            }
        });
        return form;
    }

    private String normalizePath(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(RETRY_DELAY_MS * attempt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BrokerApiException("Zerodha API request interrupted", ex);
        }
    }
}
