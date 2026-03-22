package com.broker.gateway.zerodha;

import com.broker.exception.SessionNotInitializedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Service
@ConditionalOnProperty(name = "zerodha.enabled", havingValue = "true")
public class ZerodhaSessionManager {

    private final AtomicReference<SessionState> sessionState = new AtomicReference<>();
    private final String defaultUserId;

    public ZerodhaSessionManager() {
        this("");
    }

    @Autowired
    public ZerodhaSessionManager(@Value("${ZERODHA_USER_ID:}") String defaultUserId) {
        this.defaultUserId = defaultUserId;
    }

    public void setSession(String apiKey, String accessToken, String userId) {
        sessionState.set(new SessionState(apiKey, accessToken, userId, Instant.now()));
    }

    public void setSession(String apiKey, String accessToken) {
        sessionState.set(new SessionState(apiKey, accessToken, defaultUserId, Instant.now()));
    }

    public void clearSession() {
        sessionState.set(null);
    }

    public boolean hasActiveSession() {
        return sessionState.get() != null;
    }

    public SessionState getSession() {
        SessionState state = sessionState.get();
        if (state == null) {
            throw new SessionNotInitializedException();
        }
        return state;
    }

    public String getApiKey() {
        return getSession().apiKey();
    }

    public String getAccessToken() {
        return getSession().accessToken();
    }

    public String getUserId() {
        return getSession().userId();
    }

    public Instant getLoginTimestamp() {
        return getSession().loginTimestamp();
    }

    public String getMaskedApiKey() {
        if (!hasActiveSession()) {
            return "N/A";
        }
        String apiKey = sessionState.get().apiKey();
        if (apiKey == null || apiKey.isBlank() || apiKey.length() <= 4) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****";
    }

    public record SessionState(
            String apiKey,
            String accessToken,
            String userId,
            Instant loginTimestamp
    ) {
    }
}
