package com.broker.tools;

import com.broker.service.ZerodhaTradebookService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "broker.tools.mode", havingValue = "full")
@ConditionalOnProperty(name = "zerodha.enabled", havingValue = "true")
public class ZerodhaTradebookWriteTools {

    private final ZerodhaTradebookService zerodhaTradebookService;
    private final ObjectMapper objectMapper;

    public ZerodhaTradebookWriteTools(ZerodhaTradebookService zerodhaTradebookService, ObjectMapper objectMapper) {
        this.zerodhaTradebookService = zerodhaTradebookService;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "import_zerodha_tradebook", description = """
            Import a Zerodha Console tradebook CSV from a server-accessible file path.

            The path must be inside zerodha.tradebook.import-root.
            Use this to persist Zerodha trade history for FIFO tax calculations in long-running MCP sessions.
            """)
    public String importTradebook(
            @McpToolParam(description = "CSV path inside the configured Zerodha import root, for example '/imports/zerodha-tradebook.csv'") String path) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    zerodhaTradebookService.importTradebook(path)
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize Zerodha tradebook import result", ex);
        }
    }
}
