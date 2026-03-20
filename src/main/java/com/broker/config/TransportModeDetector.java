package com.broker.config;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TransportModeDetector {

    public enum TransportMode {
        STDIO,
        HTTP_SSE,
        HTTP_STREAMABLE,
        HTTP_STATELESS
    }

    private final boolean stdio;
    private final String webApplicationType;
    private final McpServerProperties.ServerProtocol protocol;

    public TransportModeDetector() {
        this(true, "none", McpServerProperties.ServerProtocol.SSE);
    }

    public TransportModeDetector(
            @Value("${spring.ai.mcp.server.stdio:false}") boolean stdio,
            @Value("${spring.main.web-application-type:none}") String webApplicationType,
            @Value("${spring.ai.mcp.server.protocol:SSE}") McpServerProperties.ServerProtocol protocol) {
        this.stdio = stdio;
        this.webApplicationType = webApplicationType == null ? "none" : webApplicationType.trim().toLowerCase();
        this.protocol = protocol == null ? McpServerProperties.ServerProtocol.SSE : protocol;
    }

    public TransportMode getCurrentMode() {
        if (stdio || "none".equals(webApplicationType)) {
            return TransportMode.STDIO;
        }
        return switch (protocol) {
            case STREAMABLE -> TransportMode.HTTP_STREAMABLE;
            case STATELESS -> TransportMode.HTTP_STATELESS;
            case SSE -> TransportMode.HTTP_SSE;
        };
    }

    public boolean isStdioMode() {
        return getCurrentMode() == TransportMode.STDIO;
    }

    public boolean isHttpMode() {
        return !isStdioMode();
    }

    public String getModeDescription() {
        return switch (getCurrentMode()) {
            case STDIO -> "stdio (local/Claude Desktop)";
            case HTTP_SSE -> "http/sse";
            case HTTP_STREAMABLE -> "http/streamable";
            case HTTP_STATELESS -> "http/stateless";
        };
    }
}
