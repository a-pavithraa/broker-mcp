package com.broker.tools;

import com.broker.exception.BreezeApiException;
import com.broker.model.AnalysisModels.*;
import com.broker.service.BrokerDataProvider;
import com.broker.service.CompoundToolService;
import com.broker.service.CompoundToolServiceTestFactory;
import com.broker.service.StockMetadataService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TradingToolsTest {

    @Test
    void executeTrade_shouldForwardBrokerRouting() {
        TrackingCompoundToolService service = new TrackingCompoundToolService();
        TradingTools tools = new TradingTools(service, true, true);

        String result = tools.executeTrade("ICICI Bank", "buy", 5, "market", null, false, "NSE", "zerodha");

        assertEquals("{\"tool\":\"execute_trade\"}", result);
        assertEquals("zerodha", service.lastExecuteBroker);
    }

    @Test
    void setStopLosses_shouldForwardBrokerRouting() {
        TrackingCompoundToolService service = new TrackingCompoundToolService();
        TradingTools tools = new TradingTools(service, true, true);

        String result = tools.setStopLosses(List.of("TATPOW"), 10, false, "NSE", "icici");

        assertEquals("{\"tool\":\"set_stop_losses\"}", result);
        assertEquals("icici", service.lastStopLossBroker);
    }

    @Test
    void executeTrade_shouldAllowDefaultBrokerRouting() {
        TrackingCompoundToolService service = new TrackingCompoundToolService();
        TradingTools tools = new TradingTools(service, true, false);

        tools.executeTrade("ICICI Bank", "buy", 5, "market", null, false, "NSE", null);

        assertNull(service.lastExecuteBroker);
    }

    @Test
    void shouldRejectUnavailableBrokerSelection() {
        TradingTools tools = new TradingTools(new TrackingCompoundToolService(), true, false);

        BreezeApiException error = assertThrows(BreezeApiException.class,
                () -> tools.executeTrade("ICICI Bank", "buy", 5, "market", null, false, "NSE", "zerodha"));

        assertEquals("Zerodha broker is not configured", error.getMessage());
    }

    @Test
    void shouldRejectInvalidBrokerValue() {
        TradingTools tools = new TradingTools(new TrackingCompoundToolService(), true, true);

        BreezeApiException error = assertThrows(BreezeApiException.class,
                () -> tools.setStopLosses(List.of("TATPOW"), 10, false, "NSE", "upstox"));

        assertEquals("broker must be 'icici' or 'zerodha'", error.getMessage());
    }

    @Test
    void tradeConfirmationAppResource_shouldUseExtAppsSdkAndConnect() throws Exception {
        String html = new ClassPathResource("mcp-apps/trade-confirmation.html")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(html.contains("@modelcontextprotocol/ext-apps"));
        assertTrue(html.contains("new App("));
        assertTrue(html.contains("app.connect()"));
    }

    private static final class TrackingCompoundToolService extends CompoundToolService {

        private String lastExecuteBroker;
        private String lastStopLossBroker;

        private TrackingCompoundToolService() {
            super(
                    CompoundToolServiceTestFactory.builder(
                                    new NoopBrokerDataProvider(),
                                    new StockMetadataService(new DefaultResourceLoader(), JsonMapper.builder().build(), "classpath:stock-universe.csv"),
                                    JsonMapper.builder().build()
                            )
                            .tradingEnabled(true)
                            .dependencies()
            );
        }

        @Override
        public String executeTrade(String stockCode, String action, int quantity, String orderType, Double price, boolean confirmed, String exchange, String broker) {
            this.lastExecuteBroker = broker;
            return "{\"tool\":\"execute_trade\"}";
        }

        @Override
        public String setStopLosses(List<String> stockCodes, double stopLossPct, boolean confirmed, String exchange, String broker) {
            this.lastStopLossBroker = broker;
            return "{\"tool\":\"set_stop_losses\"}";
        }
    }

    private static final class NoopBrokerDataProvider implements BrokerDataProvider {

        @Override
        public List<HoldingSnapshot> getPortfolioHoldings() {
            return List.of();
        }

        @Override
        public FundsSnapshot getFunds() {
            return new FundsSnapshot(0, 0, "", "stub", Map.of());
        }

        @Override
        public QuoteSnapshot getQuote(String stockCode, String exchangeCode, String productType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, QuoteSnapshot> getQuotes(List<String> stockCodes, String exchangeCode, String productType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<HistoricalCandle> getHistoricalCharts(String stockCode, String exchangeCode, String productType, String interval, LocalDate fromDate, LocalDate toDate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TradeSnapshot> getTrades(LocalDate fromDate, LocalDate toDate) {
            return List.of();
        }

        @Override
        public List<GttOrderSnapshot> getGttOrders() {
            return List.of();
        }

        @Override
        public List<OptionChainSnapshot> getOptionChain(String stockCode, String expiryDate, String right) {
            return List.of();
        }

        @Override
        public tools.jackson.databind.JsonNode previewOrder(Map<String, String> body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public tools.jackson.databind.JsonNode placeOrder(Map<String, String> body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public tools.jackson.databind.JsonNode getOrderDetail(String exchangeCode, String orderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public tools.jackson.databind.JsonNode placeGttOrder(Map<String, String> body) {
            throw new UnsupportedOperationException();
        }
    }
}
