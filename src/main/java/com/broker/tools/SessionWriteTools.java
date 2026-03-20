package com.broker.tools;

import com.broker.service.BreezeApiClient;
import com.broker.service.BreezeSessionManager;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnProperty(name = "breeze.enabled", havingValue = "true")
public class SessionWriteTools {

    private final BreezeSessionManager sessionManager;
    private final BreezeApiClient breezeApiClient;

    public SessionWriteTools(BreezeSessionManager sessionManager, BreezeApiClient breezeApiClient) {
        this.sessionManager = sessionManager;
        this.breezeApiClient = breezeApiClient;
    }

    @McpTool(name = "breeze_set_session", description = """
            Manually set Breeze API session credentials.
            Normally the session is auto-initialized on startup from environment variables
            (BREEZE_API_KEY, BREEZE_SECRET, BREEZE_SESSION in claude_desktop_config.json).
            Use this tool only when the auto-initialization failed or to refresh a session.

            This tool exchanges the raw API session for an authenticated session token
            via the /customerdetails endpoint, then validates the session.
            """)
    public String setSession(
            @McpToolParam(description = "Your Breeze API key from ICICI Direct developer portal") String apiKey,
            @McpToolParam(description = "Your Breeze API secret from ICICI Direct developer portal") String apiSecret,
            @McpToolParam(description = "API session from automation.py or manual login (the apisession value from redirect URL)") String apiSession) {

        if (apiKey == null || apiKey.isBlank()) {
            return "Error: apiKey is required";
        }
        if (apiSecret == null || apiSecret.isBlank()) {
            return "Error: apiSecret is required";
        }
        if (apiSession == null || apiSession.isBlank()) {
            return "Error: apiSession is required";
        }

        BreezeApiClient.SessionDetails session;
        try {
            session = breezeApiClient.generateSession(apiKey.trim(), apiSession.trim());
        } catch (Exception e) {
            return "Error: Failed to generate session from /customerdetails - " + e.getMessage();
        }

        sessionManager.setSession(apiKey.trim(), apiSecret.trim(), session.sessionKey(), session.userId());

        try {
            JsonNode response = breezeApiClient.get("/funds");
            if (response.has("Error") && response.get("Error").asText() != null && !response.get("Error").asText().equals("null")) {
                sessionManager.clearSession();
                return "Error: Session validation failed - " + response.get("Error").asText();
            }
        } catch (Exception e) {
            sessionManager.clearSession();
            return "Error: Session validation failed - " + e.getMessage();
        }

        return "Session set successfully! " +
                "API Key: " + sessionManager.getMaskedApiKey() + ". " +
                "Session validated and ready for use.";
    }
}
