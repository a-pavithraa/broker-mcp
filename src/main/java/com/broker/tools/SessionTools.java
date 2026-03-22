package com.broker.tools;

import com.broker.config.TransportModeDetector;
import com.broker.gateway.icici.BreezeSessionManager;
import com.broker.gateway.zerodha.ZerodhaSessionManager;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SessionTools {

    private final TransportModeDetector transportModeDetector;
    private final BreezeSessionManager sessionManager;
    private final ZerodhaSessionManager zerodhaSessionManager;
    private final ObjectMapper objectMapper;

    @Autowired
    public SessionTools(
            TransportModeDetector transportModeDetector,
            ObjectProvider<BreezeSessionManager> sessionManagerProvider,
            ObjectProvider<ZerodhaSessionManager> zerodhaSessionManagerProvider,
            ObjectMapper objectMapper) {
        this(transportModeDetector, sessionManagerProvider.getIfAvailable(), zerodhaSessionManagerProvider.getIfAvailable(), objectMapper);
    }

    SessionTools(
            TransportModeDetector transportModeDetector,
            BreezeSessionManager sessionManager,
            ZerodhaSessionManager zerodhaSessionManager,
            ObjectMapper objectMapper) {
        this.transportModeDetector = transportModeDetector;
        this.sessionManager = sessionManager;
        this.zerodhaSessionManager = zerodhaSessionManager;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "breeze_session_status", description = """
            Check the current Breeze API session status.
            Returns whether a session is active, the transport mode,
            and masked API key information.
            """)
    public String sessionStatus() {
        boolean iciciActive = sessionManager != null && sessionManager.hasActiveSession();
        boolean zerodhaActive = zerodhaSessionManager != null && zerodhaSessionManager.hasActiveSession();

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("transport_mode", transportModeDetector.getModeDescription());
        status.put("session_active", iciciActive || zerodhaActive);
        status.put("brokers", buildBrokerStatus(iciciActive, zerodhaActive));
        if (iciciActive || zerodhaActive) {
            status.put("active_brokers", activeBrokerLabels(iciciActive, zerodhaActive));
        }
        if (iciciActive) {
            status.put("api_key", sessionManager.getMaskedApiKey());
            status.put("login_time",
                    sessionManager.getLoginTimestamp().atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
            );
        }
        if (!iciciActive && !zerodhaActive) {
            status.put("next_steps", List.of(
                    "Use breeze_set_session to provide credentials manually.",
                    "Refresh the relevant broker session in your MCP server configuration and restart the client if needed."
            ));
        }
        return toJson(status);
    }

    private Map<String, Object> buildBrokerStatus(boolean iciciActive, boolean zerodhaActive) {
        Map<String, Object> brokers = new LinkedHashMap<>();
        if (sessionManager != null) {
            brokers.put("icici_breeze", buildIciciStatus(iciciActive));
        }
        if (zerodhaSessionManager != null) {
            brokers.put("zerodha_kite", buildZerodhaStatus(zerodhaActive));
        }
        return brokers;
    }

    private Map<String, Object> buildIciciStatus(boolean iciciActive) {
        Map<String, Object> icici = new LinkedHashMap<>();
        icici.put("active", iciciActive);
        if (iciciActive) {
            icici.put("api_key", sessionManager.getMaskedApiKey());
            icici.put("login_time", sessionManager.getLoginTimestamp().atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")));
        }
        return icici;
    }

    private Map<String, Object> buildZerodhaStatus(boolean zerodhaActive) {
        Map<String, Object> zerodha = new LinkedHashMap<>();
        zerodha.put("active", zerodhaActive);
        if (zerodhaActive) {
            zerodha.put("api_key", zerodhaSessionManager.getMaskedApiKey());
            zerodha.put("user_id", zerodhaSessionManager.getUserId());
            zerodha.put("login_time", zerodhaSessionManager.getLoginTimestamp().atZone(ZoneId.of("Asia/Kolkata"))
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")));
            zerodha.put("expires_at", nextZerodhaExpiry(zerodhaSessionManager.getLoginTimestamp().atZone(ZoneId.of("Asia/Kolkata")))
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")));
        }
        return zerodha;
    }

    private List<String> activeBrokerLabels(boolean iciciActive, boolean zerodhaActive) {
        List<String> active = new java.util.ArrayList<>();
        if (iciciActive) {
            active.add("icici");
        }
        if (zerodhaActive) {
            active.add("zerodha");
        }
        return active;
    }

    private ZonedDateTime nextZerodhaExpiry(ZonedDateTime loginTime) {
        ZonedDateTime nextSixAm = loginTime.toLocalDate().plusDays(1).atTime(6, 0).atZone(loginTime.getZone());
        if (!loginTime.isBefore(nextSixAm)) {
            return nextSixAm.plusDays(1);
        }
        return nextSixAm;
    }

    private String toJson(Object value) {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }
}
