package com.broker.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientConfigTest {

    @Test
    void shouldExposeRestClientBuilderBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(ClientConfig.class)
                .run(context -> assertInstanceOf(RestClient.Builder.class, context.getBean(RestClient.Builder.class)));
    }

    @Test
    void shouldApplyConfiguredTransportSettings() {
        new ApplicationContextRunner()
                .withUserConfiguration(ClientConfig.class)
                .withPropertyValues(
                        "broker.http.connect-timeout=5s",
                        "broker.http.read-timeout=12s",
                        "broker.http.force-http1=false")
                .run(context -> {
                    RestClient.Builder builder = context.getBean(RestClient.Builder.class);
                    Object requestFactory = readField(builder, "requestFactory");

                    assertInstanceOf(JdkClientHttpRequestFactory.class, requestFactory);
                    assertEquals(Duration.ofSeconds(12), readField(requestFactory, "readTimeout"));

                    HttpClient httpClient = (HttpClient) readField(requestFactory, "httpClient");
                    assertEquals(Duration.ofSeconds(5), httpClient.connectTimeout().orElseThrow());
                    assertEquals(HttpClient.Version.HTTP_2, httpClient.version());
                });
    }

    @Test
    void shouldDefaultRetryBackoffMultiplierToOne() {
        assertEquals(1.0, new BreezeConfig.Retry(3, Duration.ofMillis(500), null).multiplier());
        assertEquals(1.0, new ZerodhaConfig.Retry(2, Duration.ofMillis(250), null).multiplier());
    }

    private static Object readField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Failed to read field " + name, ex);
        }
    }
}
