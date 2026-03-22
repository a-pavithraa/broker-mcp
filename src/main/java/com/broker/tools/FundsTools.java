package com.broker.tools;

import com.broker.model.AnalysisModels.FundsSnapshot;
import com.broker.gateway.BrokerDataProvider;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class FundsTools {

    private final BrokerDataProvider dataProvider;
    private final ObjectMapper objectMapper;

    public FundsTools(BrokerDataProvider dataProvider, ObjectMapper objectMapper) {
        this.dataProvider = dataProvider;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "breeze_get_funds", description = """
            Retrieve account fund details from ICICI Direct Breeze API.

            Returns:
            - Bank account details
            - Total balance
            - Segment allocations (Equity, FNO, Commodity, Currency)
            - Trade blocks by segment
            - Unallocated balance

            Requires active session (use breeze_login or breeze_set_session first).
            """)
    public String getFunds() {
        List<FundsSnapshot> snapshots = dataProvider.getAllFunds();
        if (snapshots.isEmpty()) {
            return toJson(Map.of(
                    "status", "ERROR",
                    "message", "No active broker funds available"
            ));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("funds", snapshots.stream()
                .map(this::toBrokerFunds)
                .toList());
        return toJson(result);
    }

    private Map<String, Object> toBrokerFunds(FundsSnapshot snapshot) {
        Map<String, Object> brokerFunds = new LinkedHashMap<>();
        brokerFunds.put("broker", snapshot.broker());
        brokerFunds.put("total_balance", snapshot.totalBalance());
        brokerFunds.put("unallocated_balance", snapshot.unallocatedBalance());
        if (snapshot.bankAccount() != null && !snapshot.bankAccount().isBlank()) {
            brokerFunds.put("bank_account", snapshot.bankAccount());
        }
        brokerFunds.put("details", snapshot.details() == null ? Map.of() : snapshot.details());
        return brokerFunds;
    }

    private String toJson(Object value) {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }
}
