package com.broker.config;

import com.broker.service.ZerodhaSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ZerodhaSessionInitializerTest {

    @Test
    void run_shouldInitializeSessionWhenRequiredEnvVarsExist() throws Exception {
        ZerodhaSessionManager sessionManager = new ZerodhaSessionManager();
        ZerodhaSessionInitializer initializer = new ZerodhaSessionInitializer(sessionManager, Map.of(
                "ZERODHA_API_KEY", "kite1234",
                "ZERODHA_ACCESS_TOKEN", "access-token",
                "ZERODHA_USER_ID", "ZU1234"
        ));

        initializer.run(new DefaultApplicationArguments(new String[0]));

        assertTrue(sessionManager.hasActiveSession());
        assertEquals("kite1234", sessionManager.getApiKey());
        assertEquals("access-token", sessionManager.getAccessToken());
        assertEquals("ZU1234", sessionManager.getUserId());
    }

    @Test
    void run_shouldSkipInitializationWhenRequiredEnvVarsAreMissing() throws Exception {
        ZerodhaSessionManager sessionManager = new ZerodhaSessionManager();
        ZerodhaSessionInitializer initializer = new ZerodhaSessionInitializer(sessionManager, Map.of(
                "ZERODHA_API_KEY", "kite1234"
        ));

        initializer.run(new DefaultApplicationArguments(new String[0]));

        assertFalse(sessionManager.hasActiveSession());
    }
}
