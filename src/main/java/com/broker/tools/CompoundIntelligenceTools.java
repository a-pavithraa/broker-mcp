package com.broker.tools;

import com.broker.exception.BreezeApiException;
import com.broker.exception.BrokerCapabilityException;
import com.broker.service.CompoundToolService;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class CompoundIntelligenceTools {

    private final CompoundToolService compoundToolService;
    private final ObjectMapper objectMapper;

    public CompoundIntelligenceTools(CompoundToolService compoundToolService, ObjectMapper objectMapper) {
        this.compoundToolService = compoundToolService;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "portfolio_snapshot", description = """
            Return a portfolio-level snapshot with totals, today's movers, top holdings, and overall winners/losers.

            Use this when the user asks how their portfolio is doing today.
            On closed-market days, use the returned market_session block and treat daily change fields as last-session data, not live intraday movement.
            """, metaProvider = McpAppsCspProvider.PortfolioDashboard.class)
    public String portfolioSnapshot() {
        return runTool("portfolio_snapshot", compoundToolService::portfolioSnapshot);
    }

    @McpTool(name = "stock_checkup", description = """
            Analyze a single stock with live price levels, current price quote, portfolio position, moving averages, momentum, and volume trend.

            Use this when the user asks for a stock quote, LTP, current price, price check, or a broader stock checkup.
            Accepts either an ICICI stock code or a common stock name for well-known seeded metadata entries.
            """)
    public String stockCheckup(
            @McpToolParam(description = "ICICI stock code or common name, for example 'TATPOW' or 'Tata Power'") String stockCode,
            @McpToolParam(description = "Exchange: 'NSE', 'BSE', or 'BOTH' for side-by-side comparison. Defaults to NSE.", required = false) String exchange) {
        return runTool("stock_checkup", () -> compoundToolService.stockCheckup(stockCode, exchange));
    }

    @McpTool(name = "portfolio_health", description = """
            Assess the portfolio for concentration risk, sector exposure, heuristic risk flags, and data coverage.
            Returns structured output with facts, heuristics, scope_limitations, and data_coverage sections.
            The facts section includes market_session so closed-market days are not misdescribed as live "today" moves.
            Use when the user asks about portfolio health, risk, or concentration.
            """)
    public String portfolioHealth() {
        return runTool("portfolio_health", compoundToolService::portfolioHealth);
    }

    @McpTool(name = "tax_harvest_report", description = """
            Analyze capital gains tax liability and loss harvesting opportunities
            for the current Indian financial year (April–March).
            Use when the user asks about tax, capital gains, STCG, LTCG, tax saving,
            or tax harvesting on equity holdings.
            """, metaProvider = McpAppsCspProvider.TaxHarvestReport.class)
    public String taxHarvestReport() {
        return runTool("tax_harvest_report", compoundToolService::taxHarvestReport);
    }

    @McpTool(name = "order_preview", description = """
            Preview the estimated order cost or proceeds, including brokerage and charge breakdown.
            This is read-only and does not place any orders.
            Use market_session and validation messages to warn when the market is closed.
            """)
    public String orderPreview(
            @McpToolParam(description = "ICICI stock code or common name") String stockCode,
            @McpToolParam(description = "buy or sell") String action,
            @McpToolParam(description = "Number of shares") int quantity,
            @McpToolParam(description = "market or limit", required = false) String orderType,
            @McpToolParam(description = "Limit price, required only for limit orders", required = false) Double price,
            @McpToolParam(description = "Exchange: 'NSE' or 'BSE'. Defaults to NSE.", required = false) String exchange,
            @McpToolParam(description = "Optional broker route override: 'icici' or 'zerodha'. Use for holdings-specific sell previews.", required = false) String broker) {
        return runTool("order_preview", () -> compoundToolService.orderPreview(stockCode, action, quantity, orderType, price, exchange, broker));
    }

    @McpTool(name = "market_pulse", description = """
            Summarize the current market using Nifty, Bank Nifty, recent trend, and Nifty option-chain sentiment.
            On closed-market days, use market_session and treat quoted day changes as the last completed session.
            """)
    public String marketPulse() {
        return runTool("market_pulse", compoundToolService::marketPulse);
    }

    @McpResource(
            name = "Portfolio Dashboard",
            uri = "ui://portfolio/portfolio-dashboard.html",
            description = "Interactive portfolio dashboard with summary cards, holdings table, sector allocation chart, and daily movers",
            mimeType = "text/html;profile=mcp-app",
            metaProvider = McpAppsCspProvider.class)
    public String portfolioDashboardApp() throws IOException {
        return new ClassPathResource("mcp-apps/portfolio-dashboard.html")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @McpResource(
            name = "Tax Harvest Report",
            uri = "ui://tax/tax-harvest-report.html",
            description = "Interactive tax harvest report with LTCG/STCG analysis and harvest candidate preview",
            mimeType = "text/html;profile=mcp-app",
            metaProvider = McpAppsCspProvider.class)
    public String taxHarvestReportApp() throws IOException {
        return new ClassPathResource("mcp-apps/tax-harvest-report.html")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private String runTool(String toolName, Supplier<String> supplier) {
        try {
            return supplier.get();
        } catch (BrokerCapabilityException ex) {
            return unavailable(toolName, ex.getMessage(), "Use another configured broker for market data or enable the required Zerodha paid tier.");
        } catch (BreezeApiException ex) {
            if (looksLikeSessionIssue(ex)) {
                return unavailable(toolName, ex.getMessage(), "Refresh the active broker session and retry.");
            }
            throw ex;
        }
    }

    private boolean looksLikeSessionIssue(BreezeApiException ex) {
        String message = ex.getMessage();
        if (message == null) {
            return ex.getStatusCode() == 401;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return ex.getStatusCode() == 401
                || normalized.contains("session")
                || normalized.contains("token")
                || normalized.contains("unauthor")
                || normalized.contains("expired");
    }

    private String unavailable(String toolName, String message, String recommendation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "UNAVAILABLE");
        payload.put("tool", toolName);
        payload.put("message", message);
        payload.put("recommendation", recommendation);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize tool-unavailable response", e);
        }
    }
}
