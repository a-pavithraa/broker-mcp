# REST Client Modernization Design

**Date:** 2026-03-24

**Goal:** Modernize outbound broker API calls to use Spring Boot 4's preferred HTTP client style without changing broker-facing behavior or widening scope into a general gateway redesign.

## Current State

The application is already on Spring Boot 4.0.3 and Java 25, but outbound HTTP calls still use raw `java.net.http.HttpClient` directly in application services.

Current call sites:
- `src/main/java/com/broker/config/ClientConfig.java`
- `src/main/java/com/broker/gateway/icici/BreezeApiClient.java`
- `src/main/java/com/broker/gateway/zerodha/ZerodhaApiClient.java`
- `src/main/java/com/broker/gateway/zerodha/ZerodhaInstrumentCache.java`

This creates a few avoidable problems:
- request building, headers, and error handling are hand-rolled in service classes
- tests are coupled to JDK `HttpClient` implementation details
- the code does not use Spring's preferred Boot 4 client abstraction

## Scope

In scope:
- replace direct `HttpClient` usage in broker HTTP clients with Spring `RestClient`
- preserve existing synchronous APIs and return types
- preserve current broker authentication and session handling responsibilities
- preserve current JSON request and response shapes
- move HTTP-focused tests toward request/response verification instead of `HttpClient` subclassing

Out of scope:
- moving to reactive `WebClient`
- redesigning broker gateway interfaces
- changing domain models returned by broker services
- broad retry-policy expansion beyond what is needed to preserve current behavior
- unrelated package restructuring

## Options Considered

### 1. Incremental migration to Spring `RestClient` with synchronous services

Keep the existing service contracts and migrate only the outbound HTTP implementation details.

Pros:
- aligns with Spring Boot 4 expectations
- low migration risk
- keeps current service layering intact
- improves testability at the HTTP boundary

Cons:
- still leaves some broker-specific request shaping inside service classes
- retry and error policy remain mostly local unless explicitly extracted

### 2. Full migration to `WebClient`

Use Spring's reactive client for all outbound broker communication.

Pros:
- very flexible client stack
- strong support for filters and advanced non-blocking workflows

Cons:
- introduces reactive complexity the current codebase does not need
- larger rewrite for little immediate payoff

### 3. Keep `HttpClient` but wrap it behind local abstractions

Create internal wrappers but stay on the JDK client.

Pros:
- smallest API churn

Cons:
- misses the main modernization goal
- weaker Spring integration than `RestClient`

**Decision:** Option 1.

## Design

### Architecture

`ClientConfig` will provide Spring-managed `RestClient` configuration for outbound broker traffic instead of exposing a shared raw `HttpClient` bean for application services to use directly.

The broker client classes will keep their current public methods:
- `BreezeApiClient`
- `ZerodhaApiClient`
- the HTTP downloader inside `ZerodhaInstrumentCache`

Internally, those classes will use `RestClient` request builders to:
- build URLs
- apply broker-specific headers
- send JSON or form bodies
- read response bodies as `String` or `byte[]`
- translate HTTP failures into existing `BrokerApiException` behavior

### Responsibilities

Responsibilities remain intentionally narrow:
- `ClientConfig`: shared Spring HTTP client configuration
- `BreezeApiClient`: Breeze-specific auth headers, rate limiting, and response parsing
- `ZerodhaApiClient`: Zerodha-specific auth headers, query/form/json request construction, and response parsing
- `ZerodhaInstrumentCache`: instrument CSV download and decompression

No new cross-cutting broker abstraction is required for this change.

### Data Flow

Normal request flow after modernization:
1. Service method receives the existing inputs.
2. Broker client builds the broker-specific URL and headers.
3. Broker client sends the request through `RestClient`.
4. Response body is read as text or bytes.
5. Existing JSON parsing and broker-specific response validation run.
6. Existing domain-facing return type is preserved.

### Error Handling

The migration should preserve current behavior first.

Required behavior:
- HTTP error statuses still become `BrokerApiException`
- current retry behavior for transient `503` responses stays intact
- interrupted waits still restore the thread interrupt flag
- JSON serialization and parsing failures still map to broker-specific exceptions

Nice-to-have but non-blocking for the first pass:
- centralize reusable status handling
- later widen transient retry coverage to `429` and selected `5xx` responses

### Testing Strategy

Testing should split into two layers.

Unit tests:
- pure helpers
- request parameter building
- session guard behavior
- cache freshness and parsing logic
- isolated retry decisions if extracted into helper methods

HTTP boundary tests:
- use WireMock for broker client request/response verification
- assert URL, query params, headers, and body serialization
- assert response parsing and error translation
- assert retry behavior with scripted transient failures

This avoids brittle tests that subclass JDK `HttpClient` while still keeping fast unit coverage where no real HTTP interaction is needed.

## Files Expected To Change

- `src/main/java/com/broker/config/ClientConfig.java`
- `src/main/java/com/broker/gateway/icici/BreezeApiClient.java`
- `src/main/java/com/broker/gateway/zerodha/ZerodhaApiClient.java`
- `src/main/java/com/broker/gateway/zerodha/ZerodhaInstrumentCache.java`
- `src/test/java/com/broker/gateway/icici/BreezeApiClientTest.java`
- `src/test/java/com/broker/gateway/zerodha/ZerodhaApiClientTest.java`
- possible new focused tests around the instrument downloader if needed

## Verification

Minimum verification expected during implementation:
- targeted tests for Breeze client
- targeted tests for Zerodha client
- targeted tests for instrument download path if behavior changes there
- application context tests still pass for the enabled profiles

## Success Criteria

The modernization is complete when:
- broker HTTP call sites no longer build and send raw JDK `HttpRequest` objects in application services
- Spring `RestClient` is the outbound HTTP mechanism for the broker integrations in scope
- current broker-facing behavior remains unchanged
- tests validate the HTTP boundary with stable, implementation-independent assertions
