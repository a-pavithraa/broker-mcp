package com.broker.tools;

import org.springframework.ai.mcp.annotation.context.MetaProvider;

import java.util.List;
import java.util.Map;

public class McpAppsCspProvider implements MetaProvider {

    private static final Map<String, Object> CSP =
            Map.of("resourceDomains", List.of("https://cdn.jsdelivr.net"));

    @Override
    public Map<String, Object> getMeta() {
        return Map.of("ui", Map.of("csp", CSP));
    }

    public static class PortfolioDashboard implements MetaProvider {
        @Override
        public Map<String, Object> getMeta() {
            return Map.of("ui", Map.of("resourceUri", "ui://portfolio/portfolio-dashboard.html"));
        }
    }

    public static class TaxHarvestReport implements MetaProvider {
        @Override
        public Map<String, Object> getMeta() {
            return Map.of("ui", Map.of("resourceUri", "ui://tax/tax-harvest-report.html"));
        }
    }

    public static class TradeConfirmation implements MetaProvider {
        @Override
        public Map<String, Object> getMeta() {
            return Map.of("ui", Map.of("resourceUri", "ui://trading/trade-confirmation.html"));
        }
    }
}
