package com.broker.config;

import com.broker.gateway.zerodha.ZerodhaSessionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Function;

@Component
@Order(1)
@ConditionalOnProperty(name = "zerodha.enabled", havingValue = "true")
public class ZerodhaSessionInitializer implements ApplicationRunner {

    private final ZerodhaSessionManager sessionManager;
    private final Function<String, String> environmentReader;

    @Autowired
    public ZerodhaSessionInitializer(ZerodhaSessionManager sessionManager) {
        this(sessionManager, System.getenv()::get);
    }

    ZerodhaSessionInitializer(ZerodhaSessionManager sessionManager, Map<String, String> environment) {
        this(sessionManager, environment::get);
    }

    ZerodhaSessionInitializer(ZerodhaSessionManager sessionManager, Function<String, String> environmentReader) {
        this.sessionManager = sessionManager;
        this.environmentReader = environmentReader;
    }

    @Override
    public void run(ApplicationArguments args) {
        String apiKey = read("ZERODHA_API_KEY");
        String accessToken = read("ZERODHA_ACCESS_TOKEN");
        String userId = read("ZERODHA_USER_ID");

        if (apiKey == null || apiKey.isBlank()
                || accessToken == null || accessToken.isBlank()) {
            System.err.println("[Zerodha] ZERODHA_API_KEY / ZERODHA_ACCESS_TOKEN not set — session not auto-initialized.");
            return;
        }

        sessionManager.setSession(apiKey, accessToken, userId);
        System.err.println("[Zerodha] Session auto-initialized" + (userId == null || userId.isBlank() ? "." : " for user: " + userId));
    }

    private String read(String key) {
        String value = environmentReader.apply(key);
        return value == null ? null : value.trim();
    }
}
