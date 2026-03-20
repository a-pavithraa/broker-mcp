package com.broker.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "zerodha.tradebook")
@ConditionalOnProperty(name = "zerodha.enabled", havingValue = "true")
public record ZerodhaTradebookProperties(String importRoot, String storePath) {

    public Path importRootPath() {
        return Path.of(importRoot).toAbsolutePath().normalize();
    }

    public Path storeFilePath() {
        return Path.of(storePath).toAbsolutePath().normalize();
    }
}
