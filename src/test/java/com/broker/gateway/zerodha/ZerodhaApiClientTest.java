package com.broker.gateway.zerodha;

import com.broker.exception.BrokerApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ZerodhaApiClientTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void get_shouldRetryTransient503Responses() {
        RecordingHttpClient httpClient = new RecordingHttpClient(
                response(503, "{\"status\":\"error\",\"message\":\"busy\"}"),
                response(200, "{\"status\":\"success\",\"data\":{\"ok\":true}}")
        );
        ZerodhaSessionManager sessionManager = new ZerodhaSessionManager("ZERODHA_USER");
        sessionManager.setSession("api_key", "access_token");
        ZerodhaApiClient client = new ZerodhaApiClient(httpClient, objectMapper, sessionManager, "https://api.kite.trade");

        assertEquals(true, client.get("/quote").path("data").path("ok").asBoolean());
        assertEquals(2, httpClient.calls);
    }

    @Test
    void get_shouldThrowAfterRetryBudgetIsExhausted() {
        RecordingHttpClient httpClient = new RecordingHttpClient(
                response(503, "{\"status\":\"error\",\"message\":\"busy\"}"),
                response(503, "{\"status\":\"error\",\"message\":\"still busy\"}"),
                response(503, "{\"status\":\"error\",\"message\":\"still busy\"}")
        );
        ZerodhaSessionManager sessionManager = new ZerodhaSessionManager("ZERODHA_USER");
        sessionManager.setSession("api_key", "access_token");
        ZerodhaApiClient client = new ZerodhaApiClient(httpClient, objectMapper, sessionManager, "https://api.kite.trade");

        assertThrows(BrokerApiException.class, () -> client.get("/quote"));
        assertEquals(3, httpClient.calls);
    }

    @Test
    void sessionManager_shouldUseConfiguredDefaultUserIdWhenSessionUserIsOmitted() {
        ZerodhaSessionManager sessionManager = new ZerodhaSessionManager("ZERODHA_USER");

        sessionManager.setSession("api_key", "access_token");

        assertEquals("ZERODHA_USER", sessionManager.getUserId());
    }

    private static ResponseSpec response(int statusCode, String body) {
        return new ResponseSpec(statusCode, body);
    }

    private record ResponseSpec(int statusCode, String body) {
    }

    private static final class RecordingHttpClient extends HttpClient {
        private final List<ResponseSpec> responses;
        private int calls;

        private RecordingHttpClient(ResponseSpec... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            ResponseSpec response = responses.get(Math.min(calls, responses.size() - 1));
            calls++;
            @SuppressWarnings("unchecked")
            HttpResponse<T> cast = (HttpResponse<T>) new StubHttpResponse(request, response.statusCode(), response.body());
            return cast;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }

    private static final class StubHttpResponse implements HttpResponse<String> {
        private final HttpRequest request;
        private final int statusCode;
        private final String body;

        private StubHttpResponse(HttpRequest request, int statusCode, String body) {
            this.request = request;
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (a, b) -> true);
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
