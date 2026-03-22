package com.broker.tools;

import com.broker.reference.CorporateActionService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class CorporateActionTools {

    private final CorporateActionService corporateActionService;
    private final ObjectMapper objectMapper;

    public CorporateActionTools(CorporateActionService corporateActionService, ObjectMapper objectMapper) {
        this.corporateActionService = corporateActionService;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "list_corporate_actions", description = """
            List the reviewed corporate action registry used by tax-harvest FIFO processing.

            Use this to inspect currently configured splits, bonuses, and manual allotment hints.
            Entries are loaded from the bundled seed file plus the external writable store.
            """)
    public String listCorporateActions() {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    corporateActionService.listActions()
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize corporate action registry", ex);
        }
    }
}
