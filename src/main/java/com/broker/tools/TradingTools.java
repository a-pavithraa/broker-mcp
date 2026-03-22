package com.broker.tools;

import com.broker.exception.BrokerApiException;
import com.broker.gateway.icici.BreezeGatewayService;
import com.broker.analysis.CompoundToolService;
import com.broker.gateway.zerodha.ZerodhaGatewayService;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "broker.trading.enabled", havingValue = "true")
public class TradingTools {

    private final CompoundToolService compoundToolService;
    private final boolean iciciConfigured;
    private final boolean zerodhaConfigured;

    public TradingTools(
            CompoundToolService compoundToolService,
            ObjectProvider<BreezeGatewayService> breezeGatewayServiceProvider,
            ObjectProvider<ZerodhaGatewayService> zerodhaGatewayServiceProvider) {
        this(compoundToolService, breezeGatewayServiceProvider.getIfAvailable() != null, zerodhaGatewayServiceProvider.getIfAvailable() != null);
    }

    TradingTools(CompoundToolService compoundToolService, boolean iciciConfigured, boolean zerodhaConfigured) {
        this.compoundToolService = compoundToolService;
        this.iciciConfigured = iciciConfigured;
        this.zerodhaConfigured = zerodhaConfigured;
    }

    @McpTool(name = "execute_trade", description = """
            Preview or execute a trade. Only available when trading mode is enabled.

            IMPORTANT: This places real orders with real money.
            Always call once with confirmed=false first to review the preview,
            then call again with confirmed=true only after explicit user confirmation.
            Optional broker routing: use broker='icici' or broker='zerodha' when both are configured.
            """, metaProvider = McpAppsCspProvider.TradeConfirmation.class)
    public String executeTrade(
            @McpToolParam(description = "ICICI stock code or common name") String stockCode,
            @McpToolParam(description = "buy or sell") String action,
            @McpToolParam(description = "Number of shares") int quantity,
            @McpToolParam(description = "market or limit", required = false) String orderType,
            @McpToolParam(description = "Limit price, required only for limit orders", required = false) Double price,
            @McpToolParam(description = "false returns preview, true places the order") boolean confirmed,
            @McpToolParam(description = "Exchange: 'NSE' or 'BSE'. Defaults to NSE.", required = false) String exchange,
            @McpToolParam(description = "Optional broker routing: 'icici' or 'zerodha'. Omit to use the default broker.", required = false) String broker) {
        return compoundToolService.executeTrade(stockCode, action, quantity, orderType, price, confirmed, exchange, validateBroker(broker));
    }

    @McpTool(name = "set_stop_losses", description = """
            Preview or place stop-loss GTT orders for selected stocks or the full portfolio.
            Only available when trading mode is enabled.

            IMPORTANT: This places real GTT orders.
            Always call once with confirmed=false first to review,
            then call again with confirmed=true only after explicit user confirmation.
            Optional broker routing: use broker='icici' or broker='zerodha' when both are configured.
            """)
    public String setStopLosses(
            @McpToolParam(description = "Optional list of stock codes or common names. Leave empty for all holdings.", required = false)
            List<String> stockCodes,
            @McpToolParam(description = "Percentage below current price to place the stop loss") double stopLossPct,
            @McpToolParam(description = "false returns preview, true places GTT orders") boolean confirmed,
            @McpToolParam(description = "Exchange: 'NSE' or 'BSE'. Defaults to NSE.", required = false) String exchange,
            @McpToolParam(description = "Optional broker routing: 'icici' or 'zerodha'. Omit to use the default broker.", required = false) String broker) {
        return compoundToolService.setStopLosses(stockCodes, stopLossPct, confirmed, exchange, validateBroker(broker));
    }

    @McpResource(
            name = "Trade Confirmation",
            uri = "ui://trading/trade-confirmation.html",
            description = "Interactive trade form with live preview, cost breakdown, and confirmation gate",
            mimeType = "text/html;profile=mcp-app",
            metaProvider = McpAppsCspProvider.class)
    public String tradeConfirmationApp() throws IOException {
        return new ClassPathResource("mcp-apps/trade-confirmation.html")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private String validateBroker(String broker) {
        if (broker == null || broker.isBlank()) {
            return null;
        }
        String normalized = broker.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("icici", "zerodha").contains(normalized)) {
            throw new BrokerApiException("broker must be 'icici' or 'zerodha'");
        }
        if ("icici".equals(normalized) && !iciciConfigured) {
            throw new BrokerApiException("ICICI broker is not configured");
        }
        if ("zerodha".equals(normalized) && !zerodhaConfigured) {
            throw new BrokerApiException("Zerodha broker is not configured");
        }
        return normalized;
    }
}
