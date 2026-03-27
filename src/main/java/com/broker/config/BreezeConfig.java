package com.broker.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "breeze")
@ConditionalOnProperty(name = "breeze.enabled", havingValue = "true")
public record BreezeConfig(String baseUrl, Security security, Retry retry) {

    public BreezeConfig {
        if (security == null) security = new Security(Map.of(), List.of());
        if (retry == null) retry = new Retry(3, Duration.ofMillis(500), null);
    }

    public record Security(Map<String, String> apiKeys, List<String> corsOrigins) {
        public Security {
            if (apiKeys == null) apiKeys = Map.of();
            if (corsOrigins == null) corsOrigins = List.of();
        }
    }

    public record Retry(int maxRetries, Duration delay, Double multiplier) {
        public Retry {
            if (delay == null) delay = Duration.ofMillis(500);
            if (maxRetries < 0) maxRetries = 0;
            if (multiplier == null || multiplier < 1.0) multiplier = 1.0;
        }
    }
}
