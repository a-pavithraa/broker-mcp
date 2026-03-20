package com.broker.service;

import com.broker.exception.BreezeApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

@Service
@Order(2)
@ConditionalOnProperty(name = "zerodha.enabled", havingValue = "true")
public class ZerodhaInstrumentCache implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaInstrumentCache.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final LocalTime SESSION_RESET = LocalTime.of(6, 0);
    private static final List<String> EXCHANGES = List.of("NSE", "BSE", "NFO");
    private static final String CACHE_FILE_NAME = "zerodha-instruments.cache";
    private static final String META_FILE_NAME = "zerodha-instruments.meta";
    private static final Map<String, String> INDEX_SYMBOL_MAP = Map.of(
            "NIFTY", "NSE:NIFTY 50",
            "CNXBAN", "NSE:NIFTY BANK",
            "NIFTYMIDCAP", "NSE:NIFTY MIDCAP 100"
    );

    private final Path cacheFile;
    private final Path metaFile;
    private final Clock clock;
    private final CsvDownloader downloader;

    private volatile Map<String, Long> instrumentTokens = Map.of();
    private volatile Map<String, List<DerivativeInstrument>> optionContractsByUnderlying = Map.of();
    private volatile Instant loadedAt;

    @Autowired
    public ZerodhaInstrumentCache(
            ZerodhaSessionManager sessionManager,
            HttpClient httpClient,
            @Value("${zerodha.base-url:https://api.kite.trade}") String baseUrl,
            @Value("${zerodha.instrument-cache.dir:${user.home}/.broker-mcp}") String cacheDir) {
        this(Path.of(cacheDir), Clock.system(INDIA), new HttpCsvDownloader(httpClient, baseUrl, sessionManager));
    }

    ZerodhaInstrumentCache(Path cacheDir, Clock clock, CsvDownloader downloader) {
        this.cacheFile = cacheDir.resolve(CACHE_FILE_NAME);
        this.metaFile = cacheDir.resolve(META_FILE_NAME);
        this.clock = clock.withZone(INDIA);
        this.downloader = downloader;
    }

    @Override
    public void run(ApplicationArguments args) {
        initialize();
    }

    synchronized void initialize() {
        try {
            if (hasFreshCache()) {
                loadFromFile(cacheFile);
                return;
            }
            String mergedCsv = downloadAndMergeCsv();
            writeCache(mergedCsv, Instant.now(clock));
            loadFromCsv(mergedCsv);
        } catch (Exception ex) {
            if (Files.exists(cacheFile)) {
                log.warn("Unable to refresh Zerodha instrument cache, falling back to stale file: {}", ex.getMessage());
                loadFromFile(cacheFile);
                return;
            }
            log.warn("Unable to initialize Zerodha instrument cache: {}", ex.getMessage());
        }
    }

    public String resolveQuoteInstrument(String exchange, String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        String hardcoded = INDEX_SYMBOL_MAP.get(normalizedSymbol);
        if (hardcoded != null) {
            return hardcoded;
        }
        return composeKey(exchange, normalizedSymbol);
    }

    public OptionalLong findInstrumentToken(String exchange, String symbol) {
        ensureLoaded();
        String quoteInstrument = resolveQuoteInstrument(exchange, symbol);
        Long token = instrumentTokens.get(quoteInstrument);
        return token == null ? OptionalLong.empty() : OptionalLong.of(token);
    }

    public long getInstrumentToken(String exchange, String symbol) {
        return findInstrumentToken(exchange, symbol)
                .orElseThrow(() -> new BreezeApiException("No Zerodha instrument token found for " + resolveQuoteInstrument(exchange, symbol)));
    }

    public List<DerivativeInstrument> findOptionContracts(String underlying, LocalDate expiry, String right) {
        ensureLoaded();
        String normalizedUnderlying = normalizeSymbol(underlying);
        String instrumentType = switch (normalizeSymbol(right)) {
            case "CALL", "CE" -> "CE";
            case "PUT", "PE" -> "PE";
            default -> normalizeSymbol(right);
        };
        return optionContractsByUnderlying.getOrDefault(normalizedUnderlying, List.of()).stream()
                .filter(contract -> expiry == null || expiry.equals(contract.expiry()))
                .filter(contract -> instrumentType.isBlank() || instrumentType.equals(contract.instrumentType()))
                .toList();
    }

    private void ensureLoaded() {
        if (instrumentTokens.isEmpty()) {
            initialize();
        }
    }

    private boolean hasFreshCache() {
        if (!Files.exists(cacheFile) || !Files.exists(metaFile)) {
            return false;
        }
        Instant lastFetchedAt = readLastFetchedAt();
        return lastFetchedAt != null && !lastFetchedAt.isBefore(dayBoundary());
    }

    private Instant readLastFetchedAt() {
        try {
            String raw = Files.readString(metaFile, StandardCharsets.UTF_8).trim();
            return raw.isBlank() ? null : Instant.parse(raw);
        } catch (Exception ex) {
            log.warn("Unable to read Zerodha instrument cache metadata: {}", ex.getMessage());
            return null;
        }
    }

    private Instant dayBoundary() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime boundary = now.with(SESSION_RESET);
        if (now.toLocalTime().isBefore(SESSION_RESET)) {
            boundary = boundary.minusDays(1);
        }
        return boundary.toInstant();
    }

    private String downloadAndMergeCsv() throws IOException, InterruptedException {
        StringBuilder merged = new StringBuilder();
        boolean headerWritten = false;
        for (String exchange : EXCHANGES) {
            String csv = downloader.download(exchange);
            String[] lines = csv.split("\\R");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isBlank()) {
                    continue;
                }
                if (i == 0) {
                    if (!headerWritten) {
                        merged.append(line).append('\n');
                        headerWritten = true;
                    }
                    continue;
                }
                merged.append(line).append('\n');
            }
        }
        return merged.toString();
    }

    private void writeCache(String csv, Instant fetchedAt) throws IOException {
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, csv, StandardCharsets.UTF_8);
        Files.writeString(metaFile, fetchedAt.toString(), StandardCharsets.UTF_8);
    }

    private void loadFromFile(Path file) {
        try {
            loadFromCsv(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new BreezeApiException("Failed to load Zerodha instrument cache from " + file, ex);
        }
    }

    private void loadFromCsv(String csv) {
        String[] lines = csv.split("\\R");
        if (lines.length == 0 || lines[0].isBlank()) {
            instrumentTokens = Map.of();
            optionContractsByUnderlying = Map.of();
            loadedAt = Instant.now(clock);
            return;
        }

        String[] header = parseCsvLine(lines[0]);
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            columns.put(header[i].trim().toLowerCase(Locale.ROOT), i);
        }

        Integer tokenIndex = columns.get("instrument_token");
        Integer symbolIndex = columns.get("tradingsymbol");
        Integer exchangeIndex = columns.get("exchange");
        Integer nameIndex = columns.get("name");
        Integer expiryIndex = columns.get("expiry");
        Integer strikeIndex = columns.get("strike");
        Integer instrumentTypeIndex = columns.get("instrument_type");
        if (tokenIndex == null || symbolIndex == null || exchangeIndex == null) {
            throw new BreezeApiException("Zerodha instruments CSV is missing required columns");
        }

        Map<String, Long> parsed = new HashMap<>();
        Map<String, List<DerivativeInstrument>> contracts = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isBlank()) {
                continue;
            }
            String[] values = parseCsvLine(line);
            String exchange = normalizeSymbol(cell(values, exchangeIndex));
            String symbol = normalizeSymbol(cell(values, symbolIndex));
            String rawToken = cell(values, tokenIndex).trim();
            if (exchange.isBlank() || symbol.isBlank() || rawToken.isBlank()) {
                continue;
            }
            try {
                long token = Long.parseLong(rawToken);
                parsed.put(composeKey(exchange, symbol), token);
                DerivativeInstrument contract = parseDerivativeInstrument(
                        token,
                        exchange,
                        symbol,
                        nameIndex == null ? "" : cell(values, nameIndex),
                        expiryIndex == null ? "" : cell(values, expiryIndex),
                        strikeIndex == null ? "" : cell(values, strikeIndex),
                        instrumentTypeIndex == null ? "" : cell(values, instrumentTypeIndex)
                );
                if (contract != null) {
                    contracts.computeIfAbsent(contract.underlying(), ignored -> new java.util.ArrayList<>()).add(contract);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        instrumentTokens = Map.copyOf(parsed);
        optionContractsByUnderlying = contracts.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        loadedAt = Instant.now(clock);
    }

    private DerivativeInstrument parseDerivativeInstrument(
            long token,
            String exchange,
            String tradingsymbol,
            String rawName,
            String rawExpiry,
            String rawStrike,
            String rawInstrumentType
    ) {
        if (!"NFO".equals(exchange)) {
            return null;
        }
        String instrumentType = normalizeSymbol(rawInstrumentType);
        if (!"CE".equals(instrumentType) && !"PE".equals(instrumentType)) {
            return null;
        }
        String underlying = normalizeSymbol(rawName);
        if (underlying.isBlank()) {
            underlying = guessUnderlyingFromSymbol(tradingsymbol, instrumentType);
        }
        if (underlying.isBlank()) {
            return null;
        }
        LocalDate expiry = parseDate(rawExpiry);
        double strike = parseDouble(rawStrike);
        return new DerivativeInstrument(token, underlying, normalizeSymbol(tradingsymbol), expiry, strike, instrumentType);
    }

    private static String[] parseCsvLine(String line) {
        return line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
    }

    private static String cell(String[] values, int index) {
        if (index < 0 || index >= values.length) {
            return "";
        }
        String value = values[index].trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String composeKey(String exchange, String symbol) {
        return exchange.trim().toUpperCase(Locale.ROOT) + ":" + symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeSymbol(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDate.parse(value.trim());
    }

    private static double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String guessUnderlyingFromSymbol(String tradingsymbol, String instrumentType) {
        String normalized = normalizeSymbol(tradingsymbol);
        int index = normalized.indexOf(instrumentType);
        if (index <= 0) {
            return "";
        }
        String prefix = normalized.substring(0, index);
        int firstDigit = -1;
        for (int i = 0; i < prefix.length(); i++) {
            if (Character.isDigit(prefix.charAt(i))) {
                firstDigit = i;
                break;
            }
        }
        return firstDigit <= 0 ? prefix : prefix.substring(0, firstDigit);
    }

    public record DerivativeInstrument(
            long instrumentToken,
            String underlying,
            String tradingsymbol,
            LocalDate expiry,
            double strikePrice,
            String instrumentType
    ) {
        public String quoteInstrument() {
            return ZerodhaInstrumentCache.composeKey("NFO", tradingsymbol);
        }
    }

    @FunctionalInterface
    interface CsvDownloader {
        String download(String exchange) throws IOException, InterruptedException;
    }

    private static final class HttpCsvDownloader implements CsvDownloader {

        private final HttpClient httpClient;
        private final String baseUrl;
        private final ZerodhaSessionManager sessionManager;

        private HttpCsvDownloader(HttpClient httpClient, String baseUrl, ZerodhaSessionManager sessionManager) {
            this.httpClient = httpClient;
            this.baseUrl = baseUrl;
            this.sessionManager = sessionManager;
        }

        @Override
        public String download(String exchange) throws IOException, InterruptedException {
            if (!sessionManager.hasActiveSession()) {
                throw new BreezeApiException("Zerodha session is not initialized");
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/instruments/" + exchange))
                    .header("X-Kite-Version", "3")
                    .header("Authorization", "token " + sessionManager.getApiKey() + ":" + sessionManager.getAccessToken())
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new BreezeApiException("Zerodha instruments download failed for " + exchange + ": HTTP " + response.statusCode());
            }
            return decodeBody(response.body());
        }

        private String decodeBody(byte[] bytes) throws IOException {
            try (InputStream inputStream = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (ZipException ex) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }
}
