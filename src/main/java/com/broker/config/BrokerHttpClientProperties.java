package com.broker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "broker.http")
public record BrokerHttpClientProperties(
        Duration connectTimeout,
        Duration readTimeout,
        Boolean forceHttp1
) {

    public BrokerHttpClientProperties {
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(30);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(60);
        }
        if (forceHttp1 == null) {
            forceHttp1 = true;
        }
    }
}
