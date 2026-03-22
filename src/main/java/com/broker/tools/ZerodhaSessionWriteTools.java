package com.broker.tools;

import com.broker.gateway.zerodha.ZerodhaSessionManager;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

@Component
@ConditionalOnProperty(name = "zerodha.enabled", havingValue = "true")
public class ZerodhaSessionWriteTools {

    private final ZerodhaSessionManager sessionManager;
    private final ObjectMapper objectMapper;
    private final Function<String, String> environmentReader;

    @Autowired
    public ZerodhaSessionWriteTools(ZerodhaSessionManager sessionManager, ObjectMapper objectMapper) {
        this(sessionManager, objectMapper, System.getenv()::get);
    }

    ZerodhaSessionWriteTools(
            ZerodhaSessionManager sessionManager,
            ObjectMapper objectMapper,
            Function<String, String> environmentReader) {
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
        this.environmentReader = environmentReader;
    }

    @McpTool(name = "zerodha_set_session", description = """
            Manually set Zerodha Kite Connect session credentials.
            Use this when the access token has been refreshed outside the JVM
            and you need to inject it into a running server without restart.
            """)
    public String setSession(
            @McpToolParam(description = "Fresh Zerodha access token") String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return toJson(Map.of(
                    "status", "ERROR",
                    "message", "accessToken is required"
            ));
        }

        String apiKey = read("ZERODHA_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return toJson(Map.of(
                    "status", "ERROR",
                    "message", "ZERODHA_API_KEY is not available in the process environment"
            ));
        }

        String userId = read("ZERODHA_USER_ID");
        sessionManager.setSession(apiKey, accessToken.trim(), userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("api_key", sessionManager.getMaskedApiKey());
        if (sessionManager.getUserId() != null && !sessionManager.getUserId().isBlank()) {
            result.put("user_id", sessionManager.getUserId());
        }
        result.put("message", "Zerodha session updated successfully.");
        return toJson(result);
    }

    private String read(String key) {
        String value = environmentReader.apply(key);
        return value == null ? null : value.trim();
    }

    private String toJson(Object value) {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }
}
