package com.broker.config;

import com.broker.gateway.icici.BreezeApiClient;
import com.broker.gateway.icici.BreezeSessionManager;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "breeze.enabled", havingValue = "true")
public class BreezeSessionInitializer implements ApplicationRunner {

    private final BreezeSessionManager sessionManager;
    private final BreezeApiClient breezeApiClient;

    public BreezeSessionInitializer(BreezeSessionManager sessionManager, BreezeApiClient breezeApiClient) {
        this.sessionManager = sessionManager;
        this.breezeApiClient = breezeApiClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        String apiKey = System.getenv("BREEZE_API_KEY");
        String apiSecret = System.getenv("BREEZE_SECRET");
        String apiSession = System.getenv("BREEZE_SESSION");

        if (apiKey == null || apiKey.isBlank()
                || apiSecret == null || apiSecret.isBlank()
                || apiSession == null || apiSession.isBlank()) {
            System.err.println("[Breeze] BREEZE_API_KEY / BREEZE_SECRET / BREEZE_SESSION not set — session not auto-initialized.");
            return;
        }

        try {
            BreezeApiClient.SessionDetails session = breezeApiClient.generateSession(apiKey.trim(), apiSession.trim());
            sessionManager.setSession(apiKey.trim(), apiSecret.trim(), session.sessionKey(), session.userId());
            System.err.println("[Breeze] Session auto-initialized for user: " + session.userId());
        } catch (Exception e) {
            System.err.println("[Breeze] Failed to auto-initialize session new: " + e.getMessage());
        }
    }
}
