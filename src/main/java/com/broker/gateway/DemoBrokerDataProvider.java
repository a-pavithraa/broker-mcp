package com.broker.gateway;

import com.broker.model.AnalysisModels.FundsSnapshot;
import com.broker.model.AnalysisModels.GttOrderSnapshot;
import com.broker.model.AnalysisModels.HistoricalCandle;
import com.broker.model.AnalysisModels.HoldingSnapshot;
import com.broker.model.AnalysisModels.OptionChainSnapshot;
import com.broker.model.AnalysisModels.QuoteSnapshot;
import com.broker.model.AnalysisModels.TradeSnapshot;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Primary
@Profile("demo")
public class DemoBrokerDataProvider implements BrokerDataProvider {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final List<HoldingSnapshot> holdings;
    private final List<FundsSnapshot> funds;
    private final List<TradeSnapshot> trades;
    private final List<GttOrderSnapshot> gttOrders;
    private final Map<String, DemoInstrument> instruments;

    public DemoBrokerDataProvider(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock.withZone(INDIA);
        this.holdings = demoHoldings();
        this.funds = demoFunds();
        this.trades = demoTrades();
        this.gttOrders = List.of(
                new GttOrderSnapshot("DEMO-GTT-1001", "INFY", 12, "icici"),
                new GttOrderSnapshot("DEMO-GTT-1002", "TATAPOWER", 18, "icici"),
                new GttOrderSnapshot("DEMO-GTT-1003", "RELIANCE", 7, "icici"),
                new GttOrderSnapshot("DEMO-GTT-1004", "SBIN", 20, "icici")
        );
        this.instruments = demoInstruments();
    }

    @Override
    public List<HoldingSnapshot> getPortfolioHoldings() {
        return holdings;
    }

    @Override
    public FundsSnapshot getFunds() {
        return aggregateFunds(funds);
    }

    @Override
    public List<FundsSnapshot> getAllFunds() {
        return funds;
    }

    @Override
    public QuoteSnapshot getQuote(String stockCode, String exchangeCode, String productType) {
        DemoInstrument instrument = instrument(stockCode, exchangeCode);
        return instrument.toQuoteSnapshot(normalizeSymbol(stockCode), normalizeExchange(exchangeCode), now());
    }

    @Override
    public Map<String, QuoteSnapshot> getQuotes(List<String> stockCodes, String exchangeCode, String productType) {
        Map<String, QuoteSnapshot> quotes = new LinkedHashMap<>();
        for (String stockCode : stockCodes) {
            quotes.put(normalizeSymbol(stockCode), getQuote(stockCode, exchangeCode, productType));
        }
        return quotes;
    }

    @Override
    public List<HistoricalCandle> getHistoricalCharts(
            String stockCode,
            String exchangeCode,
            String productType,
            String interval,
            LocalDate fromDate,
            LocalDate toDate
    ) {
        if (fromDate == null || toDate == null || fromDate.isAfter(toDate)) {
            return List.of();
        }
        DemoInstrument instrument = instrument(stockCode, exchangeCode);
        long days = ChronoUnit.DAYS.between(fromDate, toDate);
        List<HistoricalCandle> history = new ArrayList<>();
        for (int i = 0; i <= days; i++) {
            LocalDate date = fromDate.plusDays(i);
            long remaining = ChronoUnit.DAYS.between(date, toDate);
            double wave = Math.sin(i / 6.0) * instrument.waveAmplitude();
            double close = Math.max(1, instrument.ltp() - (remaining * instrument.dailyTrend()) + wave);
            double open = close - Math.max(0.6, instrument.intradayRange() * 0.35);
            double high = close + instrument.intradayRange();
            double low = Math.max(0.5, close - instrument.intradayRange());
            double volume = instrument.baseVolume() + (i * instrument.volumeSlope());
            history.add(new HistoricalCandle(
                    date.atTime(MARKET_CLOSE).atZone(INDIA),
                    round2(open),
                    round2(high),
                    round2(low),
                    round2(close),
                    round2(volume),
                    0
            ));
        }
        return history;
    }

    @Override
    public List<TradeSnapshot> getTrades(LocalDate fromDate, LocalDate toDate) {
        return trades.stream()
                .filter(trade -> trade.tradeDate() != null)
                .filter(trade -> fromDate == null || !trade.tradeDate().isBefore(fromDate))
                .filter(trade -> toDate == null || !trade.tradeDate().isAfter(toDate))
                .toList();
    }

    @Override
    public List<GttOrderSnapshot> getGttOrders() {
        return gttOrders;
    }

    @Override
    public List<OptionChainSnapshot> getOptionChain(String stockCode, String expiryDate, String right) {
        if (!"NIFTY".equals(normalizeSymbol(stockCode))) {
            return List.of();
        }
        if ("call".equalsIgnoreCase(right)) {
            return List.of(
                    new OptionChainSnapshot(22400, 92_000),
                    new OptionChainSnapshot(22500, 110_000),
                    new OptionChainSnapshot(22600, 98_000),
                    new OptionChainSnapshot(22700, 81_000)
            );
        }
        return List.of(
                new OptionChainSnapshot(22400, 120_000),
                new OptionChainSnapshot(22500, 146_000),
                new OptionChainSnapshot(22600, 132_000),
                new OptionChainSnapshot(22700, 101_000)
        );
    }

    @Override
    public JsonNode previewOrder(Map<String, String> body) {
        double quantity = parseDouble(body == null ? null : body.get("quantity"));
        double price = parseDouble(body == null ? null : body.get("price"));
        double grossValue = quantity * price;
        double brokerage = Math.min(20, grossValue * 0.0003);
        double stt = grossValue * 0.001;
        double exchangeCharges = grossValue * 0.0000345;
        double stampDuty = "buy".equalsIgnoreCase(body == null ? null : body.get("action")) ? grossValue * 0.00015 : 0;
        double sebiCharges = grossValue * 0.000001;
        double gst = (brokerage + exchangeCharges + sebiCharges) * 0.18;
        return success(Map.of(
                "brokerage", round2(brokerage),
                "exchange_turnover_charges", round2(exchangeCharges),
                "stamp_duty", round2(stampDuty),
                "stt", round2(stt),
                "sebi_charges", round2(sebiCharges),
                "gst", round2(gst)
        ));
    }

    @Override
    public JsonNode placeOrder(Map<String, String> body) {
        String symbol = normalizeSymbol(body == null ? null : body.get("symbol"));
        return success(Map.of(
                "order_id", "DEMO-ORDER-1001",
                "status", "COMPLETE",
                "message", "Filled in demo mode for " + symbol
        ));
    }

    @Override
    public JsonNode getOrderDetail(String exchangeCode, String orderId) {
        return success(Map.of(
                "order_id", orderId == null ? "DEMO-ORDER-1001" : orderId,
                "status", "COMPLETE",
                "message", "Demo order completed"
        ));
    }

    @Override
    public JsonNode placeGttOrder(Map<String, String> body) {
        String symbol = normalizeSymbol(body == null ? null : body.get("symbol"));
        return success(Map.of(
                "order_id", "DEMO-GTT-" + symbol,
                "message", "Demo GTT created"
        ));
    }

    private List<HoldingSnapshot> demoHoldings() {
        return List.of(
                new HoldingSnapshot("INFY", "Infosys", "NSE", 12, 1490, 1815, 0, 1.74, "icici", "INE009A01021"),
                new HoldingSnapshot("TATAPOWER", "Tata Power", "NSE", 18, 378, 422, 0, 1.44, "icici", "INE245A01021"),
                new HoldingSnapshot("HDFCBANK", "HDFC Bank", "NSE", 10, 1710, 1580, 0, -0.88, "icici", "INE040A01034"),
                new HoldingSnapshot("ASIANPAINT", "Asian Paints", "NSE", 6, 3150, 2820, 0, -0.88, "icici", "INE021A01026"),
                new HoldingSnapshot("RELIANCE", "Reliance Industries", "NSE", 7, 2460, 3010, 0, 0.92, "icici", "INE002A01018"),
                new HoldingSnapshot("SBIN", "State Bank of India", "NSE", 20, 715, 812, 0, 1.18, "icici", "INE062A01020"),
                new HoldingSnapshot("SUNPHARMA", "Sun Pharmaceutical Industries", "NSE", 9, 1710, 1682, 0, -0.64, "icici", "INE044A01036"),
                new HoldingSnapshot("LT", "Larsen & Toubro", "NSE", 5, 3520, 3668, 0, 0.71, "icici", "INE018A01030")
        );
    }

    private List<FundsSnapshot> demoFunds() {
        return List.of(
                new FundsSnapshot(285_000, 64_250, "ICICI xxxx9021", "icici", Map.of(
                        "equity_available", "54250.0",
                        "fno_available", "10000.0",
                        "commodity_available", "0.0",
                        "collateral", "35000.0"
                )),
                new FundsSnapshot(92_000, 38_000, "", "zerodha", Map.of(
                        "equity_available", "38000.0",
                        "commodity_available", "0.0",
                        "collateral", "22000.0",
                        "span_used", "0.0",
                        "exposure_used", "0.0"
                ))
        );
    }

    private List<TradeSnapshot> demoTrades() {
        return List.of(
                new TradeSnapshot("INFY", "buy", 20, 1410, LocalDate.of(2024, 6, 10), "icici"),
                new TradeSnapshot("INFY", "sell", 8, 1705, LocalDate.of(2025, 8, 20), "icici"),
                new TradeSnapshot("TATAPOWER", "buy", 18, 378, LocalDate.of(2025, 5, 8), "icici"),
                new TradeSnapshot("HDFCBANK", "buy", 10, 1710, LocalDate.of(2025, 10, 2), "icici"),
                new TradeSnapshot("ASIANPAINT", "buy", 6, 3150, LocalDate.of(2024, 1, 12), "icici"),
                new TradeSnapshot("RELIANCE", "buy", 10, 2460, LocalDate.of(2024, 9, 18), "icici"),
                new TradeSnapshot("RELIANCE", "sell", 3, 2925, LocalDate.of(2025, 11, 14), "icici"),
                new TradeSnapshot("SBIN", "buy", 20, 715, LocalDate.of(2025, 7, 1), "icici"),
                new TradeSnapshot("SUNPHARMA", "buy", 9, 1710, LocalDate.of(2025, 12, 12), "icici"),
                new TradeSnapshot("LT", "buy", 6, 3520, LocalDate.of(2024, 4, 5), "icici"),
                new TradeSnapshot("LT", "sell", 1, 3740, LocalDate.of(2025, 9, 4), "icici"),
                new TradeSnapshot("AXISBANK", "buy", 12, 1040, LocalDate.of(2025, 4, 15), "icici"),
                new TradeSnapshot("AXISBANK", "sell", 12, 1188, LocalDate.of(2025, 12, 19), "icici"),
                new TradeSnapshot("ICICIBANK", "buy", 15, 1090, LocalDate.of(2025, 2, 14), "icici"),
                new TradeSnapshot("ICICIBANK", "sell", 15, 1275, LocalDate.of(2025, 12, 9), "icici")
        );
    }

    private Map<String, DemoInstrument> demoInstruments() {
        Map<String, DemoInstrument> instrumentMap = new LinkedHashMap<>();
        instrumentMap.put(key("NIFTY", "NSE"), new DemoInstrument(22540, 22460, 22490, 22590, 22420, 0, 0.85, 42, 38, 0));
        instrumentMap.put(key("CNXBAN", "NSE"), new DemoInstrument(48480, 48190, 48230, 48610, 48100, 0, 1.9, 78, 82, 0));
        instrumentMap.put(key("INFY", "NSE"), new DemoInstrument(1815, 1784, 1792, 1822, 1781, 1_860_000, 0.62, 16, 18, 3_100));
        instrumentMap.put(key("INFY", "BSE"), new DemoInstrument(1813, 1782, 1790, 1820, 1779, 820_000, 0.60, 15, 18, 1_700));
        instrumentMap.put(key("TATAPOWER", "NSE"), new DemoInstrument(422, 416, 418, 425, 414, 3_240_000, 0.44, 7, 6, 4_500));
        instrumentMap.put(key("TATAPOWER", "BSE"), new DemoInstrument(421.4, 415.4, 417.2, 424.8, 413.8, 1_480_000, 0.42, 6, 6, 2_100));
        instrumentMap.put(key("HDFCBANK", "NSE"), new DemoInstrument(1580, 1594, 1592, 1601, 1574, 2_120_000, 0.30, 10, 12, 2_400));
        instrumentMap.put(key("HDFCBANK", "BSE"), new DemoInstrument(1579.4, 1593.2, 1591.6, 1599.8, 1573.5, 760_000, 0.28, 9, 12, 1_200));
        instrumentMap.put(key("ASIANPAINT", "NSE"), new DemoInstrument(2820, 2845, 2840, 2858, 2815, 540_000, 0.24, 15, 16, 900));
        instrumentMap.put(key("ASIANPAINT", "BSE"), new DemoInstrument(2818.6, 2843.2, 2838.4, 2854.2, 2812.8, 210_000, 0.22, 14, 16, 450));
        instrumentMap.put(key("ICICIBANK", "NSE"), new DemoInstrument(1260, 1248, 1250, 1268, 1243, 2_940_000, 0.38, 11, 11, 2_800));
        instrumentMap.put(key("ICICIBANK", "BSE"), new DemoInstrument(1258.8, 1247.2, 1249.1, 1266.3, 1241.9, 1_160_000, 0.36, 10, 11, 1_300));
        instrumentMap.put(key("RELIANCE", "NSE"), new DemoInstrument(3010, 2982, 2991, 3024, 2978, 1_640_000, 0.55, 18, 21, 2_600));
        instrumentMap.put(key("RELIANCE", "BSE"), new DemoInstrument(3007.4, 2979.8, 2989.5, 3020.6, 2974.7, 690_000, 0.53, 17, 21, 1_100));
        instrumentMap.put(key("SBIN", "NSE"), new DemoInstrument(812, 802.5, 805, 815.6, 799.4, 4_120_000, 0.49, 9, 8, 5_100));
        instrumentMap.put(key("SBIN", "BSE"), new DemoInstrument(811.2, 801.8, 804.2, 814.9, 798.8, 1_520_000, 0.46, 8, 8, 2_200));
        instrumentMap.put(key("SUNPHARMA", "NSE"), new DemoInstrument(1682, 1692.8, 1688.5, 1696.4, 1676.2, 1_180_000, 0.27, 13, 10, 1_700));
        instrumentMap.put(key("SUNPHARMA", "BSE"), new DemoInstrument(1680.9, 1691.7, 1687.4, 1694.8, 1674.9, 430_000, 0.25, 12, 10, 800));
        instrumentMap.put(key("LT", "NSE"), new DemoInstrument(3668, 3642.1, 3650, 3678.5, 3638.4, 980_000, 0.41, 18, 19, 1_600));
        instrumentMap.put(key("LT", "BSE"), new DemoInstrument(3664.8, 3639.7, 3647.8, 3675.2, 3635.2, 390_000, 0.39, 17, 19, 700));
        instrumentMap.put(key("AXISBANK", "NSE"), new DemoInstrument(1184, 1172.2, 1176.4, 1189.1, 1169.5, 2_280_000, 0.33, 11, 10, 2_900));
        instrumentMap.put(key("AXISBANK", "BSE"), new DemoInstrument(1182.7, 1171.1, 1175.2, 1187.6, 1168.2, 870_000, 0.31, 10, 10, 1_300));
        return instrumentMap;
    }

    private DemoInstrument instrument(String stockCode, String exchangeCode) {
        String symbol = normalizeSymbol(stockCode);
        String exchange = normalizeExchange(exchangeCode);
        DemoInstrument instrument = instruments.get(key(symbol, exchange));
        if (instrument != null) {
            return instrument;
        }
        DemoInstrument nseInstrument = instruments.get(key(symbol, "NSE"));
        if (nseInstrument != null) {
            return nseInstrument;
        }
        double anchor = 100 + Math.abs(symbol.hashCode() % 900);
        return new DemoInstrument(anchor, anchor - 2, anchor - 1, anchor + 3, anchor - 4, 120_000, 0.15, 4, 6, 600);
    }

    private FundsSnapshot aggregateFunds(List<FundsSnapshot> snapshots) {
        return new FundsSnapshot(
                snapshots.stream().mapToDouble(FundsSnapshot::totalBalance).sum(),
                snapshots.stream().mapToDouble(FundsSnapshot::unallocatedBalance).sum(),
                null,
                "demo",
                Map.of("note", "Synthetic demo balances for screen recording")
        );
    }

    private JsonNode success(Map<String, Object> payload) {
        return objectMapper.valueToTree(Map.of("Success", payload));
    }

    private String normalizeSymbol(String stockCode) {
        return stockCode == null ? "" : stockCode.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeExchange(String exchangeCode) {
        return exchangeCode == null || exchangeCode.isBlank()
                ? "NSE"
                : exchangeCode.trim().toUpperCase(Locale.ROOT);
    }

    private String key(String stockCode, String exchangeCode) {
        return normalizeSymbol(stockCode) + "|" + normalizeExchange(exchangeCode);
    }

    private double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return Double.parseDouble(value);
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record DemoInstrument(
            double ltp,
            double previousClose,
            double open,
            double high,
            double low,
            double baseVolume,
            double dailyTrend,
            double waveAmplitude,
            double intradayRange,
            double volumeSlope
    ) {

        private QuoteSnapshot toQuoteSnapshot(String stockCode, String exchangeCode, ZonedDateTime lastTradeTime) {
            return new QuoteSnapshot(
                    stockCode,
                    exchangeCode,
                    ltp,
                    previousClose,
                    open,
                    high,
                    low,
                    baseVolume,
                    ltp - 0.5,
                    ltp + 0.5,
                    lastTradeTime
            );
        }
    }
}
