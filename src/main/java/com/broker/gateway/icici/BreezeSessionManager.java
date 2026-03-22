package com.broker.gateway.icici;

import com.broker.exception.SessionNotInitializedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

@Service
@ConditionalOnProperty(name = "breeze.enabled", havingValue = "true")
public class BreezeSessionManager {

    private final AtomicReference<SessionState> sessionState = new AtomicReference<>();
    private final String defaultUserId;

    public BreezeSessionManager() {
        this("");
    }

    @Autowired
    public BreezeSessionManager(@Value("${ICICI_USER_ID:}") String defaultUserId) {
        this.defaultUserId = defaultUserId;
    }

    public void setSession(String apiKey, String secretKey, String sessionToken, String userId) {
        sessionState.set(new SessionState(apiKey, secretKey, sessionToken, userId, Instant.now()));
    }

    public void setSession(String apiKey, String secretKey, String sessionToken) {
        sessionState.set(new SessionState(apiKey, secretKey, sessionToken, defaultUserId, Instant.now()));
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

    public String getSecretKey() {
        return getSession().secretKey();
    }

    public String getSessionToken() {
        return getSession().sessionToken();
    }

    public String getUserId() {
        return getSession().userId();
    }

    public String getBase64SessionToken() {
        SessionState state = getSession();
        String tokenData = state.userId() + ":" + state.sessionToken();
        return Base64.getEncoder().encodeToString(tokenData.getBytes(StandardCharsets.UTF_8));
    }

    public Instant getLoginTimestamp() {
        return getSession().loginTimestamp();
    }

    public String getMaskedApiKey() {
        if (!hasActiveSession()) {
            return "N/A";
        }
        String apiKey = sessionState.get().apiKey();
        if (apiKey.length() <= 4) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****";
    }

    public record SessionState(
            String apiKey,
            String secretKey,
            String sessionToken,
            String userId,
            Instant loginTimestamp
    ) {}
}
