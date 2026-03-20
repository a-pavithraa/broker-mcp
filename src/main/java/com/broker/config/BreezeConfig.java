package com.broker.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "breeze")
@ConditionalOnProperty(name = "breeze.enabled", havingValue = "true")
public record BreezeConfig(String baseUrl, Security security) {

    public BreezeConfig {
        if (security == null) security = new Security(Map.of(), List.of());
    }

    public record Security(Map<String, String> apiKeys, List<String> corsOrigins) {
        public Security {
            if (apiKeys == null) apiKeys = Map.of();
            if (corsOrigins == null) corsOrigins = List.of();
        }
    }
}
