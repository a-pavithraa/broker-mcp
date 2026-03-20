package com.broker.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;

import static org.junit.jupiter.api.Assertions.*;

class TransportModeDetectorTest {

    @Test
    void shouldDetectStdioMode() {
        TransportModeDetector detector = new TransportModeDetector(true, "none", McpServerProperties.ServerProtocol.SSE);

        assertTrue(detector.isStdioMode());
        assertFalse(detector.isHttpMode());
        assertEquals(TransportModeDetector.TransportMode.STDIO, detector.getCurrentMode());
    }

    @Test
    void shouldDetectHttpSseMode() {
        TransportModeDetector detector = new TransportModeDetector(false, "servlet", McpServerProperties.ServerProtocol.SSE);

        assertFalse(detector.isStdioMode());
        assertTrue(detector.isHttpMode());
        assertEquals(TransportModeDetector.TransportMode.HTTP_SSE, detector.getCurrentMode());
    }

    @Test
    void shouldDetectHttpStreamableMode() {
        TransportModeDetector detector = new TransportModeDetector(false, "servlet", McpServerProperties.ServerProtocol.STREAMABLE);

        assertEquals(TransportModeDetector.TransportMode.HTTP_STREAMABLE, detector.getCurrentMode());
        assertEquals("http/streamable", detector.getModeDescription());
    }

    @Test
    void stdioShouldWinWhenBothPropertiesArePresent() {
        TransportModeDetector detector = new TransportModeDetector(true, "servlet", McpServerProperties.ServerProtocol.STREAMABLE);

        assertEquals(TransportModeDetector.TransportMode.STDIO, detector.getCurrentMode());
        assertEquals("stdio (local/Claude Desktop)", detector.getModeDescription());
    }
}
