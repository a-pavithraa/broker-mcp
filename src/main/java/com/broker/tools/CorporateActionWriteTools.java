package com.broker.tools;

import com.broker.reference.CorporateActionService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

@Component
@ConditionalOnProperty(name = "broker.tools.mode", havingValue = "full")
public class CorporateActionWriteTools {

    private final CorporateActionService corporateActionService;
    private final ObjectMapper objectMapper;

    public CorporateActionWriteTools(CorporateActionService corporateActionService, ObjectMapper objectMapper) {
        this.corporateActionService = corporateActionService;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "upsert_corporate_action", description = """
            Add or update a reviewed corporate action entry in the external corporate-action store.

            Use this after tax_harvest_report flags needs_corporate_action_review for a stock.
            Supports SPLIT, BONUS, and ALLOTMENT entries. Writes persist to the configured external JSON store.
            """)
    public String upsertCorporateAction(
            @McpToolParam(description = "ICICI stock code, NSE symbol, or known stock name") String stockCode,
            @McpToolParam(description = "Corporate action type: SPLIT, BONUS, or ALLOTMENT") String type,
            @McpToolParam(description = "Ex-date in YYYY-MM-DD format") String exDate,
            @McpToolParam(description = "Quantity multiplier applied to open lots. Use 5.0 for a 1:5 split, 2.0 for a 1:1 bonus, and 1.0 for ALLOTMENT.") double quantityMultiplier,
            @McpToolParam(description = "Optional source URL for the reviewed action", required = false) String sourceUrl,
            @McpToolParam(description = "Optional operator notes", required = false) String notes) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    corporateActionService.upsertAction(
                            stockCode,
                            type,
                            LocalDate.parse(exDate),
                            quantityMultiplier,
                            sourceUrl,
                            notes
                    )
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to upsert corporate action", ex);
        }
    }
}
