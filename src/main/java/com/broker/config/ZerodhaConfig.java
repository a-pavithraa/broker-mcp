package com.broker.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "zerodha")
@ConditionalOnProperty(name = "zerodha.enabled", havingValue = "true")
public record ZerodhaConfig(String baseUrl, String tier, Retry retry) {

    public ZerodhaConfig {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.kite.trade";
        }
        if (tier == null || tier.isBlank()) {
            tier = "free";
        }
        if (retry == null) {
            retry = new Retry(2, Duration.ofMillis(250), null);
        }
    }

    public record Retry(int maxRetries, Duration delay, Double multiplier) {
        public Retry {
            if (delay == null) {
                delay = Duration.ofMillis(250);
            }
            if (maxRetries < 0) {
                maxRetries = 0;
            }
            if (multiplier == null || multiplier < 1.0) {
                multiplier = 1.0;
            }
        }
    }
}
