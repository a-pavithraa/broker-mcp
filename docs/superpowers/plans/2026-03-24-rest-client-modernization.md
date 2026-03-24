# REST Client Modernization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the broker integrations' raw JDK `HttpClient` request code with Spring `RestClient` while preserving synchronous behavior, broker-specific auth, and current error semantics.

**Architecture:** Keep the existing public service contracts, inject a shared Spring-managed `RestClient.Builder`, and let each broker client build its own base-URL-specific `RestClient`. Add HTTP-boundary tests with WireMock where the migration changes request execution behavior, and keep pure cache/session logic on normal unit tests.

**Tech Stack:** Java 25, Spring Boot 4.0.3, Spring Framework 7 `RestClient`, JUnit 5, WireMock 3, Maven

---

## File Structure

- `src/main/java/com/broker/config/ClientConfig.java`
  Responsibility: provide `JsonMapper` and a Spring-managed `RestClient.Builder` backed by `JdkClientHttpRequestFactory` with the existing timeout/compression behavior.
- `src/main/java/com/broker/gateway/zerodha/ZerodhaApiClient.java`
  Responsibility: move Zerodha GET/form/JSON calls onto `RestClient` while preserving retry and error mapping.
- `src/test/java/com/broker/gateway/zerodha/ZerodhaApiClientTest.java`
  Responsibility: verify Zerodha request/response behavior at the HTTP boundary with WireMock.
- `src/main/java/com/broker/gateway/icici/BreezeApiClient.java`
  Responsibility: move Breeze GET/POST/PUT/DELETE/session-generation calls onto `RestClient` while preserving locking, retry, and checksum/header behavior.
- `src/test/java/com/broker/gateway/icici/BreezeApiClientTest.java`
  Responsibility: keep existing session-guard tests and add WireMock-based HTTP behavior coverage.
- `src/main/java/com/broker/gateway/zerodha/ZerodhaInstrumentDownloadClient.java`
  Responsibility: own the HTTP download of Zerodha instrument CSV content through `RestClient` so the cache class keeps file/cache concerns only.
- `src/main/java/com/broker/gateway/zerodha/ZerodhaInstrumentCache.java`
  Responsibility: depend on the extracted downloader in production wiring while keeping the existing package-private fake-downloader constructor for cache tests.
- `src/test/java/com/broker/gateway/zerodha/ZerodhaInstrumentDownloadClientTest.java`
  Responsibility: verify instrument download auth, status handling, and gzip/plain decoding at the HTTP boundary.

### Task 1: Shared RestClient Foundation

**Files:**
- Modify: `src/main/java/com/broker/config/ClientConfig.java`
- Create: `src/test/java/com/broker/config/ClientConfigTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.broker.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ClientConfigTest {

    @Test
    void shouldExposeRestClientBuilderBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(ClientConfig.class)
                .run(context -> assertInstanceOf(RestClient.Builder.class, context.getBean(RestClient.Builder.class)));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=ClientConfigTest" test`
Expected: FAIL because `ClientConfig` does not yet expose a `RestClient.Builder` bean.

- [ ] **Step 3: Write minimal implementation**

Update `src/main/java/com/broker/config/ClientConfig.java` to replace the raw shared `HttpClient` bean with a `RestClient.Builder` bean backed by `JdkClientHttpRequestFactory`:

```java
@Bean
RestClient.Builder restClientBuilder() {
    HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(60));
    requestFactory.enableCompression(true);

    return RestClient.builder().requestFactory(requestFactory);
}
```

Keep the existing `JsonMapper` bean unchanged.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=ClientConfigTest" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/broker/config/ClientConfig.java src/test/java/com/broker/config/ClientConfigTest.java
git commit -m "refactor: add shared RestClient builder"
```

### Task 2: Migrate ZerodhaApiClient to RestClient

**Files:**
- Modify: `src/main/java/com/broker/gateway/zerodha/ZerodhaApiClient.java`
- Modify: `src/test/java/com/broker/gateway/zerodha/ZerodhaApiClientTest.java`

- [ ] **Step 1: Write the failing tests**

Replace the current `HttpClient` subclass test with WireMock-backed tests that exercise real HTTP request construction:

```java
@RegisterExtension
static WireMockExtension wireMock = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

@Test
void get_shouldSendQueryParamsAndAuthorizationHeader() {
    wireMock.stubFor(get(urlPathEqualTo("/quote"))
            .withQueryParam("i", equalTo("NSE:INFY"))
            .withHeader("X-Kite-Version", equalTo("3"))
            .withHeader("Authorization", equalTo("token api_key:access_token"))
            .willReturn(okJson("{" +
                    "\"status\":\"success\"," +
                    "\"data\":{\"ok\":true}" +
                    "}")));

    ZerodhaSessionManager sessionManager = new ZerodhaSessionManager("ZERODHA_USER");
    sessionManager.setSession("api_key", "access_token");
    ZerodhaApiClient client = new ZerodhaApiClient(RestClient.builder(), objectMapper, sessionManager, wireMock.baseUrl());

    assertTrue(client.get("/quote", Map.of("i", "NSE:INFY")).path("data").path("ok").asBoolean());
}

@Test
void get_shouldRetryTransient503Responses() {
    wireMock.stubFor(get(urlPathEqualTo("/quote"))
            .inScenario("retry")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withStatus(503).withBody("{\"status\":\"error\",\"message\":\"busy\"}"))
            .willSetStateTo("second"));
    wireMock.stubFor(get(urlPathEqualTo("/quote"))
            .inScenario("retry")
            .whenScenarioStateIs("second")
            .willReturn(okJson("{\"status\":\"success\",\"data\":{\"ok\":true}}")));

    ZerodhaSessionManager sessionManager = new ZerodhaSessionManager("ZERODHA_USER");
    sessionManager.setSession("api_key", "access_token");
    ZerodhaApiClient client = new ZerodhaApiClient(RestClient.builder(), objectMapper, sessionManager, wireMock.baseUrl());

    assertTrue(client.get("/quote").path("data").path("ok").asBoolean());
    wireMock.verify(2, getRequestedFor(urlPathEqualTo("/quote")));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=ZerodhaApiClientTest" test`
Expected: FAIL because `ZerodhaApiClient` still expects `HttpClient` and the new WireMock tests will not compile or pass.

- [ ] **Step 3: Write minimal implementation**

Update `src/main/java/com/broker/gateway/zerodha/ZerodhaApiClient.java`:

```java
private final RestClient restClient;

public ZerodhaApiClient(
        RestClient.Builder restClientBuilder,
        ObjectMapper objectMapper,
        ZerodhaSessionManager sessionManager,
        @Value("${zerodha.base-url:https://api.kite.trade}") String baseUrl) {
    this.restClient = restClientBuilder.clone().baseUrl(baseUrl).build();
    this.objectMapper = objectMapper;
    this.sessionManager = sessionManager;
}
```

Refactor request sending to use `RestClient` instead of manual URL encoding and `HttpRequest` creation:

```java
private JsonNode send(HttpMethod method, String path, Consumer<UriBuilder> queryWriter, @Nullable MediaType contentType, @Nullable Object body) {
    int attempt = 0;
    while (true) {
        try {
            RestClient.RequestBodySpec request = restClient.method(method)
                    .uri(uriBuilder -> {
                        UriBuilder builder = uriBuilder.path(path.startsWith("/") ? path : "/" + path);
                        queryWriter.accept(builder);
                        return builder.build();
                    })
                    .header("X-Kite-Version", "3")
                    .header("Authorization", "token " + sessionManager.getApiKey() + ":" + sessionManager.getAccessToken());

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
                Thread.sleep(RETRY_DELAY_MS * attempt);
                continue;
            }
            throw new BrokerApiException("Zerodha API error: HTTP " + ex.getStatusCode().value() + " " + ex.getResponseBodyAsString(), ex.getStatusCode().value());
        } catch (RestClientException ex) {
            throw new BrokerApiException("Zerodha API request failed", ex);
        } catch (JacksonException ex) {
            throw new BrokerApiException("Failed to parse Zerodha API response", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BrokerApiException("Zerodha API request interrupted", ex);
        }
    }
}
```

Use `LinkedMultiValueMap<String, String>` for `postForm(...)` so Spring writes `application/x-www-form-urlencoded` correctly.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=ZerodhaApiClientTest" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/broker/gateway/zerodha/ZerodhaApiClient.java src/test/java/com/broker/gateway/zerodha/ZerodhaApiClientTest.java
git commit -m "refactor: migrate Zerodha API client to RestClient"
```

### Task 3: Migrate BreezeApiClient to RestClient

**Files:**
- Modify: `src/main/java/com/broker/gateway/icici/BreezeApiClient.java`
- Modify: `src/test/java/com/broker/gateway/icici/BreezeApiClientTest.java`

- [ ] **Step 1: Write the failing tests**

Keep the existing session-manager assertions and add WireMock-backed tests for HTTP behavior:

```java
@RegisterExtension
static WireMockExtension wireMock = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

@Test
void get_shouldSendAuthenticatedHeadersAndRetry503() {
    BreezeSessionManager sessionManager = new BreezeSessionManager("ICICI_USER");
    sessionManager.setSession("api_key", "secret_key", "session_token", "ICICI_USER");
    BreezeApiClient client = new BreezeApiClient(
            new BreezeConfig(wireMock.baseUrl(), null),
            sessionManager,
            new BreezeChecksumGenerator(),
            JsonMapper.builder().build(),
            RestClient.builder());

    wireMock.stubFor(any(urlEqualTo("/funds"))
            .inScenario("retry")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withStatus(503).withBody("{\"Error\":\"busy\"}"))
            .willSetStateTo("second"));
    wireMock.stubFor(any(urlEqualTo("/funds"))
            .inScenario("retry")
            .whenScenarioStateIs("second")
            .withHeader("X-AppKey", equalTo("api_key"))
            .withHeader("X-SessionToken", matching(".+"))
            .withHeader("X-Timestamp", matching(".+"))
            .withHeader("X-Checksum", matching("token .+"))
            .willReturn(okJson("{\"Success\":{\"ok\":true}}")));

    assertTrue(client.get("/funds").path("Success").path("ok").asBoolean());
    wireMock.verify(2, anyRequestedFor(urlEqualTo("/funds")));
}

@Test
void generateSession_shouldDecodeReturnedToken() {
    wireMock.stubFor(any(urlEqualTo("/customerdetails"))
            .willReturn(okJson("{\"Success\":{\"session_token\":\"VVNFUjEyMzpTRVNTSU9OS0VZ\"}}")));

    BreezeApiClient client = new BreezeApiClient(
            new BreezeConfig(wireMock.baseUrl(), null),
            new BreezeSessionManager("ICICI_USER"),
            new BreezeChecksumGenerator(),
            JsonMapper.builder().build(),
            RestClient.builder());

    BreezeApiClient.SessionDetails details = client.generateSession("api", "session");

    assertEquals("USER123", details.userId());
    assertEquals("SESSIONKEY", details.sessionKey());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=BreezeApiClientTest" test`
Expected: FAIL because `BreezeApiClient` still expects `HttpClient` and does not yet execute through `RestClient`.

- [ ] **Step 3: Write minimal implementation**

Update `src/main/java/com/broker/gateway/icici/BreezeApiClient.java`:

```java
private final RestClient restClient;

public BreezeApiClient(
        BreezeConfig breezeConfig,
        BreezeSessionManager sessionManager,
        BreezeChecksumGenerator checksumGenerator,
        ObjectMapper objectMapper,
        RestClient.Builder restClientBuilder) {
    this.breezeConfig = breezeConfig;
    this.sessionManager = sessionManager;
    this.checksumGenerator = checksumGenerator;
    this.objectMapper = objectMapper;
    this.restClient = restClientBuilder.clone().baseUrl(breezeConfig.baseUrl()).build();
}
```

Refactor HTTP execution so the lock/rate-gap logic stays intact while the actual request uses `RestClient`:

```java
private JsonNode executeWithRetry(HttpMethod method, String endpoint, @Nullable MediaType contentType, String body) {
    int attempt = 0;
    while (true) {
        try {
            RestClient.RequestBodySpec request = restClient.method(method)
                    .uri(endpoint.startsWith("/") ? endpoint : "/" + endpoint)
                    .header("Content-Type", contentType.toString());

            authenticatedHeaders(body).forEach(request::header);
            if (!body.isBlank()) {
                request.body(body);
            }

            String responseBody = request.retrieve().body(String.class);
            return objectMapper.readTree(responseBody);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 503 && attempt < MAX_RETRIES) {
                attempt++;
                Thread.sleep(RETRY_DELAY_MS * attempt);
                continue;
            }
            throw new BrokerApiException("Breeze API error: " + ex.getResponseBodyAsString(), ex.getStatusCode().value());
        } catch (RestClientException ex) {
            throw new BrokerApiException("Failed to communicate with Breeze API: " + ex.getMessage(), ex);
        } catch (JacksonException ex) {
            throw new BrokerApiException("Failed to parse Breeze API response: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BrokerApiException("Request interrupted", ex);
        }
    }
}
```

For `generateSession(...)`, build a non-authenticated `GET` request through `restClient.method(HttpMethod.GET)` with `application/x-www-form-urlencoded` content and keep the existing Base64 decode logic unchanged.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=BreezeApiClientTest" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/broker/gateway/icici/BreezeApiClient.java src/test/java/com/broker/gateway/icici/BreezeApiClientTest.java
git commit -m "refactor: migrate Breeze API client to RestClient"
```

### Task 4: Extract and Migrate Zerodha Instrument Downloader

**Files:**
- Create: `src/main/java/com/broker/gateway/zerodha/ZerodhaInstrumentDownloadClient.java`
- Modify: `src/main/java/com/broker/gateway/zerodha/ZerodhaInstrumentCache.java`
- Create: `src/test/java/com/broker/gateway/zerodha/ZerodhaInstrumentDownloadClientTest.java`
- Modify: `src/test/java/com/broker/gateway/zerodha/ZerodhaInstrumentCacheTest.java`

- [ ] **Step 1: Write the failing tests**

Add an HTTP-boundary test for the downloader and keep cache tests focused on cache semantics:

```java
@Test
void download_shouldSendAuthorizationHeaderAndDecodeGzipBody() throws Exception {
    byte[] gzippedCsv = gzip("instrument_token,tradingsymbol,exchange\n408065,INFY,NSE\n");
    wireMock.stubFor(get(urlEqualTo("/instruments/NSE"))
            .withHeader("Authorization", equalTo("token api_key:access_token"))
            .withHeader("X-Kite-Version", equalTo("3"))
            .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Encoding", "gzip")
                    .withBody(gzippedCsv)));

    ZerodhaSessionManager sessionManager = new ZerodhaSessionManager("ZERODHA_USER");
    sessionManager.setSession("api_key", "access_token");
    ZerodhaInstrumentDownloadClient client = new ZerodhaInstrumentDownloadClient(RestClient.builder(), wireMock.baseUrl(), sessionManager);

    assertTrue(client.download("NSE").contains("INFY"));
}
```

In `ZerodhaInstrumentCacheTest`, keep using the package-private `CsvDownloader` constructor and do not replace cache tests with HTTP tests.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=ZerodhaInstrumentDownloadClientTest,ZerodhaInstrumentCacheTest" test`
Expected: FAIL because the production downloader is still a private nested `HttpClient` implementation and the new top-level client does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/broker/gateway/zerodha/ZerodhaInstrumentDownloadClient.java` as a focused package-private or public Spring service:

```java
@Service
@ConditionalOnProperty(name = "zerodha.enabled", havingValue = "true")
class ZerodhaInstrumentDownloadClient implements ZerodhaInstrumentCache.CsvDownloader {

    private final RestClient restClient;
    private final ZerodhaSessionManager sessionManager;

    ZerodhaInstrumentDownloadClient(
            RestClient.Builder restClientBuilder,
            @Value("${zerodha.base-url:https://api.kite.trade}") String baseUrl,
            ZerodhaSessionManager sessionManager) {
        this.restClient = restClientBuilder.clone().baseUrl(baseUrl).build();
        this.sessionManager = sessionManager;
    }

    @Override
    public String download(String exchange) {
        if (!sessionManager.hasActiveSession()) {
            throw new BrokerApiException("Zerodha session is not initialized");
        }

        byte[] body = restClient.get()
                .uri("/instruments/{exchange}", exchange)
                .header("X-Kite-Version", "3")
                .header("Authorization", "token " + sessionManager.getApiKey() + ":" + sessionManager.getAccessToken())
                .retrieve()
                .body(byte[].class);

        return decodeBody(body);
    }
}
```

Then simplify `ZerodhaInstrumentCache` production wiring:

```java
@Autowired
public ZerodhaInstrumentCache(
        ZerodhaInstrumentDownloadClient downloader,
        @Value("${zerodha.instrument-cache.dir:${user.home}/.broker-mcp}") String cacheDir) {
    this(Path.of(cacheDir), Clock.system(INDIA), downloader);
}
```

Move the old gzip/plain decode helper onto the new downloader class so `ZerodhaInstrumentCache` only handles caching and CSV parsing.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=ZerodhaInstrumentDownloadClientTest,ZerodhaInstrumentCacheTest" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/broker/gateway/zerodha/ZerodhaInstrumentDownloadClient.java src/main/java/com/broker/gateway/zerodha/ZerodhaInstrumentCache.java src/test/java/com/broker/gateway/zerodha/ZerodhaInstrumentDownloadClientTest.java src/test/java/com/broker/gateway/zerodha/ZerodhaInstrumentCacheTest.java
git commit -m "refactor: extract Zerodha instrument download client"
```

### Task 5: Regression Verification and Context Safety

**Files:**
- Modify: `src/test/java/com/broker/config/CompositeBrokerGatewayConfigTest.java` only if a context assertion needs adjustment for the new wiring
- Test: `src/test/java/com/broker/IciciOnlyApplicationContextTest.java`
- Test: `src/test/java/com/broker/ZerodhaOnlyApplicationContextTest.java`
- Test: `src/test/java/com/broker/BothBrokersApplicationContextTest.java`

- [ ] **Step 1: Write the failing safety check if context wiring breaks**

Only if the new `RestClient.Builder` wiring causes a context regression, add the smallest failing assertion to `CompositeBrokerGatewayConfigTest` or the affected context test before changing production code.

- [ ] **Step 2: Run targeted regression tests**

Run: `mvn "-Dtest=ClientConfigTest,ZerodhaApiClientTest,BreezeApiClientTest,ZerodhaInstrumentDownloadClientTest,ZerodhaInstrumentCacheTest,IciciOnlyApplicationContextTest,ZerodhaOnlyApplicationContextTest,BothBrokersApplicationContextTest" test`
Expected: PASS.

- [ ] **Step 3: Run combined broker/config verification**

Run: `mvn "-Dtest=CompositeBrokerGatewayConfigTest,AnalysisServicesConfigTest" test`
Expected: PASS.

- [ ] **Step 4: Commit the final modernization batch**

```bash
git add src/main/java/com/broker/config/ClientConfig.java src/main/java/com/broker/gateway/zerodha/ZerodhaApiClient.java src/main/java/com/broker/gateway/icici/BreezeApiClient.java src/main/java/com/broker/gateway/zerodha/ZerodhaInstrumentDownloadClient.java src/main/java/com/broker/gateway/zerodha/ZerodhaInstrumentCache.java src/test/java/com/broker/config/ClientConfigTest.java src/test/java/com/broker/gateway/zerodha/ZerodhaApiClientTest.java src/test/java/com/broker/gateway/icici/BreezeApiClientTest.java src/test/java/com/broker/gateway/zerodha/ZerodhaInstrumentDownloadClientTest.java src/test/java/com/broker/gateway/zerodha/ZerodhaInstrumentCacheTest.java
 git commit -m "refactor: modernize broker REST clients"
```

## Notes For Implementers

- Preserve existing public methods and return types on the broker clients.
- Do not widen scope into `WebClient`, resilience libraries, or a general broker abstraction.
- Keep `ZerodhaInstrumentCacheTest` focused on cache semantics; use the new downloader test for HTTP transport behavior.
- Prefer real HTTP behavior through WireMock over mocking the `RestClient` fluent chain.
- If `RestClient` form submission needs message-converter tuning, do that in `ClientConfig` rather than special-casing individual clients.
