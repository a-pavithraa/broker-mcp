package com.broker.service;

import com.broker.config.ZerodhaTradebookProperties;
import com.broker.exception.BreezeApiException;
import com.broker.model.AnalysisModels.TradeSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@ConditionalOnProperty(name = "zerodha.enabled", havingValue = "true")
public class ZerodhaTradebookService {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaTradebookService.class);
    private static final int SCHEMA_VERSION = 1;
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH)
    );
    private static final Set<String> DERIVATIVE_SEGMENT_MARKERS = Set.of("NFO", "F&O", "FNO", "FUT", "OPT", "MCX", "CDS");

    private final ZerodhaTradebookProperties properties;
    private final StockMetadataService stockMetadataService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Object monitor = new Object();

    @Autowired
    public ZerodhaTradebookService(
            ZerodhaTradebookProperties properties,
            StockMetadataService stockMetadataService,
            ObjectMapper objectMapper) {
        this(properties, stockMetadataService, objectMapper, Clock.system(INDIA));
    }

    ZerodhaTradebookService(
            ZerodhaTradebookProperties properties,
            StockMetadataService stockMetadataService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.properties = properties;
        this.stockMetadataService = stockMetadataService;
        this.objectMapper = objectMapper;
        this.clock = clock.withZone(INDIA);
    }

    public ImportSummary importTradebook(String requestedPath) {
        Path csvPath = resolveImportPath(requestedPath);
        log.info("Starting Zerodha tradebook import: requestedPath={} resolvedPath={}", requestedPath, csvPath);
        byte[] content;
        try {
            content = Files.readAllBytes(csvPath);
        } catch (IOException ex) {
            throw new BreezeApiException("Failed to read Zerodha tradebook CSV: " + csvPath, ex);
        }

        CsvParseResult parseResult = parse(csvPath, content);
        StoreImportMetadata metadata = new StoreImportMetadata(
                checksum(content),
                csvPath.toString(),
                Instant.now(clock),
                lastModified(csvPath),
                parseResult.coveredFrom(),
                parseResult.coveredTo(),
                parseResult.sourceRows(),
                parseResult.tradeEvents().size(),
                parseResult.adjustmentEvents().size(),
                parseResult.warningMessages()
        );

        synchronized (monitor) {
            // Each import is merged into the persisted JSON store so startup imports
            // can build on earlier imports instead of replacing the whole history.
            StoreState existing = loadState();
            StoreState updated = mergeState(existing, metadata, parseResult);
            persist(updated);
        }

        log.info("Imported Zerodha tradebook: path={} trades={} adjustments={} warnings={}",
                csvPath, parseResult.tradeEvents().size(), parseResult.adjustmentEvents().size(), parseResult.warningMessages().size());

        return new ImportSummary(
                csvPath.toString(),
                metadata.importedAt(),
                metadata.coveredFrom(),
                metadata.coveredTo(),
                parseResult.tradeEvents().size(),
                parseResult.adjustmentEvents().size(),
                parseResult.warningMessages()
        );
    }

    public CoverageSummary coverageSummary() {
        synchronized (monitor) {
            StoreState state = loadState();
            List<StoreImportMetadata> imports = state.imports();
            List<StoredTradeEvent> events = state.events();
            LocalDate coveredFrom = imports.stream()
                    .map(StoreImportMetadata::coveredFrom)
                    .filter(Objects::nonNull)
                    .min(LocalDate::compareTo)
                    .orElse(null);
            LocalDate coveredTo = imports.stream()
                    .map(StoreImportMetadata::coveredTo)
                    .filter(Objects::nonNull)
                    .max(LocalDate::compareTo)
                    .orElse(null);
            long unresolvedAdjustments = events.stream()
                    .filter(event -> event.requiresManualReview() && event.eventType() == EventType.ADJUSTMENT)
                    .count();
            List<String> warnings = imports.stream()
                    .flatMap(metadata -> metadata.warnings().stream())
                    .distinct()
                    .limit(20)
                    .toList();
            CoverageSummary summary = new CoverageSummary(
                    !imports.isEmpty(),
                    coveredFrom,
                    coveredTo,
                    events.stream().filter(event -> event.eventType() == EventType.TRADE).count(),
                    unresolvedAdjustments,
                    warnings
            );
            log.info("Computed Zerodha tradebook coverage: hasImports={} imports={} tradeEvents={} adjustmentEvents={} coveredRange={} unresolvedAdjustments={} warnings={}",
                    summary.hasImports(),
                    imports.size(),
                    summary.importedTrades(),
                    countEvents(events, EventType.ADJUSTMENT),
                    formatDateRange(summary.coveredFrom(), summary.coveredTo()),
                    summary.unresolvedAdjustments(),
                    summary.warnings().size());
            return summary;
        }
    }

    public List<TradeSnapshot> getImportedTrades(LocalDate fromDate, LocalDate toDate) {
        synchronized (monitor) {
            List<TradeSnapshot> trades = loadState().events().stream()
                    .filter(event -> event.eventType() == EventType.TRADE)
                    .filter(event -> !event.tradeDate().isBefore(fromDate) && !event.tradeDate().isAfter(toDate))
                    .sorted(Comparator.comparing(StoredTradeEvent::tradeDate).thenComparing(StoredTradeEvent::stockCode))
                    .map(event -> new TradeSnapshot(
                            event.stockCode(),
                            event.action(),
                            event.quantity(),
                            event.price(),
                            event.tradeDate(),
                            "zerodha"
                    ))
                    .toList();
            log.info("Loaded Zerodha imported trades: requestedRange={} returnedTrades={} returnedTradeRange={}",
                    formatDateRange(fromDate, toDate),
                    trades.size(),
                    formatTradeRange(trades));
            return trades;
        }
    }

    private StoreState mergeState(StoreState existing, StoreImportMetadata metadata, CsvParseResult parseResult) {
        // Re-importing the same CSV path replaces that file's earlier contribution,
        // but imports from other source files remain in the store.
        List<StoreImportMetadata> retainedImports = existing.imports().stream()
                .filter(importMetadata -> !importMetadata.sourcePath().equals(metadata.sourcePath()))
                .toList();
        // eventId is the cross-file dedupe key. If an imported row collides with an
        // existing stored event, the newly imported row wins.
        Map<String, StoredTradeEvent> eventsById = Stream.concat(
                        existing.events().stream()
                                .filter(event -> !event.sourcePath().equals(metadata.sourcePath())),
                        Stream.concat(parseResult.tradeEvents().stream(), parseResult.adjustmentEvents().stream()))
                .collect(Collectors.toMap(StoredTradeEvent::eventId, Function.identity(), (old, replacement) -> replacement));

        List<StoreImportMetadata> imports = new ArrayList<>(retainedImports);
        imports.add(metadata);
        imports.sort(Comparator.comparing(StoreImportMetadata::importedAt));

        List<StoredTradeEvent> events = new ArrayList<>(eventsById.values());
        events.sort(Comparator.comparing(StoredTradeEvent::tradeDate).thenComparing(StoredTradeEvent::stockCode));
        log.info("Merged Zerodha tradebook state: sourcePath={} totalImports={} tradeEvents={} adjustmentEvents={} coveredRange={}",
                metadata.sourcePath(),
                imports.size(),
                countEvents(events, EventType.TRADE),
                countEvents(events, EventType.ADJUSTMENT),
                formatDateRange(
                        imports.stream().map(StoreImportMetadata::coveredFrom).filter(Objects::nonNull).min(LocalDate::compareTo).orElse(null),
                        imports.stream().map(StoreImportMetadata::coveredTo).filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null)
                ));
        return new StoreState(SCHEMA_VERSION, imports, events);
    }

    private CsvParseResult parse(Path csvPath, byte[] content) {
        List<String> lines = content.length == 0
                ? List.of()
                : Arrays.asList(new String(content, StandardCharsets.UTF_8).split("\\R"));
        if (lines.isEmpty()) {
            throw new BreezeApiException("Zerodha tradebook CSV is empty: " + csvPath);
        }

        Map<String, Integer> columns = indexColumns(parseCsvLine(lines.getFirst()));
        int dateIndex = requireColumn(columns, "trade date", "date", "order execution time", "execution time", "time");
        int symbolIndex = requireColumn(columns, "tradingsymbol", "trading symbol", "symbol", "stock", "instrument");
        int actionIndex = requireColumn(columns, "trade type", "trade_type", "transaction type", "transaction_type", "action", "type", "side");
        int quantityIndex = requireColumn(columns, "quantity", "qty", "filled quantity", "filled_quantity");
        int priceIndex = requireColumn(columns, "price", "trade price", "trade_price", "average price", "average_price");
        int exchangeIndex = findColumn(columns, "exchange");
        int segmentIndex = findColumn(columns, "segment", "product");
        int orderIdIndex = findColumn(columns, "order id", "order_id");
        int tradeIdIndex = findColumn(columns, "trade id", "trade_id");

        List<StoredTradeEvent> tradeEvents = new ArrayList<>();
        List<StoredTradeEvent> adjustmentEvents = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        LocalDate coveredFrom = null;
        LocalDate coveredTo = null;

        for (int rowNumber = 2; rowNumber <= lines.size(); rowNumber++) {
            String line = lines.get(rowNumber - 1);
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] values = parseCsvLine(line);
            LocalDate tradeDate = parseDate(cell(values, dateIndex));
            if (tradeDate == null) {
                warnings.add("Skipped row " + rowNumber + " because trade date could not be parsed.");
                continue;
            }

            String rawSymbol = clean(cell(values, symbolIndex));
            if (rawSymbol.isBlank()) {
                warnings.add("Skipped row " + rowNumber + " because symbol was blank.");
                continue;
            }

            String segment = clean(cell(values, segmentIndex));
            if (isDerivativeSegment(segment)) {
                warnings.add("Skipped row " + rowNumber + " for " + rawSymbol + " because segment '" + segment + "' is outside cash equity scope.");
                continue;
            }

            double quantity = parseNumber(cell(values, quantityIndex));
            if (quantity <= 0) {
                warnings.add("Skipped row " + rowNumber + " for " + rawSymbol + " because quantity was not positive.");
                continue;
            }

            double price = parseNumber(cell(values, priceIndex));
            String normalizedAction = normalizeAction(clean(cell(values, actionIndex)), price);
            String stockCode = stockMetadataService.resolveNseToIcici(rawSymbol);
            String exchange = clean(cell(values, exchangeIndex));
            String orderId = clean(cell(values, orderIdIndex));
            String tradeId = clean(cell(values, tradeIdIndex));

            coveredFrom = coveredFrom == null || tradeDate.isBefore(coveredFrom) ? tradeDate : coveredFrom;
            coveredTo = coveredTo == null || tradeDate.isAfter(coveredTo) ? tradeDate : coveredTo;

            if ("buy".equals(normalizedAction) || "sell".equals(normalizedAction)) {
                if (price <= 0) {
                    // Zero-price buy/sell rows are preserved as adjustments so they
                    // stay visible for manual review instead of being silently dropped.
                    adjustmentEvents.add(new StoredTradeEvent(
                            eventId(stockCode, tradeDate, normalizedAction, quantity, price, orderId, tradeId, exchange, segment, "zero_price"),
                            metadataSource(csvPath),
                            tradeDate,
                            stockCode,
                            normalizedAction,
                            quantity,
                            price,
                            exchange,
                            segment,
                            EventType.ADJUSTMENT,
                            "ZERO_PRICE_" + normalizedAction.toUpperCase(Locale.ROOT),
                            true
                    ));
                    warnings.add("Row " + rowNumber + " for " + stockCode + " had zero price and was stored as a manual-review adjustment.");
                    continue;
                }
                // Normal cash-equity buys and sells become importable trade snapshots.
                tradeEvents.add(new StoredTradeEvent(
                        eventId(stockCode, tradeDate, normalizedAction, quantity, price, orderId, tradeId, exchange, segment, "trade"),
                        metadataSource(csvPath),
                        tradeDate,
                        stockCode,
                        normalizedAction,
                        quantity,
                        price,
                        exchange,
                        segment,
                        EventType.TRADE,
                        null,
                        false
                ));
                continue;
            }

            // Non-trade actions such as bonus/split/transfer are kept as adjustments
            // because downstream consumers cannot safely fold them into positions yet.
            adjustmentEvents.add(new StoredTradeEvent(
                    eventId(stockCode, tradeDate, normalizedAction, quantity, price, orderId, tradeId, exchange, segment, "adjustment"),
                    metadataSource(csvPath),
                    tradeDate,
                    stockCode,
                    normalizedAction,
                    quantity,
                    price,
                    exchange,
                    segment,
                    EventType.ADJUSTMENT,
                    normalizedAction.toUpperCase(Locale.ROOT),
                    true
            ));
            warnings.add("Row " + rowNumber + " for " + stockCode + " was stored as a manual-review adjustment (" + normalizedAction + ").");
        }

        return new CsvParseResult(coveredFrom, coveredTo, lines.size() - 1, tradeEvents, adjustmentEvents, List.copyOf(warnings));
    }

    private StoreState loadState() {
        Path storePath = properties.storeFilePath();
        if (!Files.exists(storePath)) {
            log.info("Zerodha tradebook store not found: path={} imports=0 events=0", storePath);
            return new StoreState(SCHEMA_VERSION, new ArrayList<>(), new ArrayList<>());
        }
        try {
            StoreState state = objectMapper.readValue(storePath.toFile(), StoreState.class);
            if (state.schemaVersion() != SCHEMA_VERSION) {
                throw new BreezeApiException("Unsupported Zerodha tradebook store schema: " + state.schemaVersion());
            }
            log.info("Loaded Zerodha tradebook store: path={} imports={} tradeEvents={} adjustmentEvents={}",
                    storePath,
                    state.imports().size(),
                    countEvents(state.events(), EventType.TRADE),
                    countEvents(state.events(), EventType.ADJUSTMENT));
            return state;
        } catch (Exception ex) {
            throw new BreezeApiException("Failed to read Zerodha tradebook store: " + storePath, ex);
        }
    }

    private void persist(StoreState state) {
        Path storePath = properties.storeFilePath();
        try {
            Path parent = storePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tempFile = Files.createTempFile(parent == null ? Path.of(".") : parent, "zerodha-tradebook", ".json.tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), state);
            moveAtomically(tempFile, storePath);
            log.info("Persisted Zerodha tradebook store: path={} imports={} tradeEvents={} adjustmentEvents={}",
                    storePath,
                    state.imports().size(),
                    countEvents(state.events(), EventType.TRADE),
                    countEvents(state.events(), EventType.ADJUSTMENT));
        } catch (Exception ex) {
            throw new BreezeApiException("Failed to persist Zerodha tradebook store: " + storePath, ex);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path resolveImportPath(String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank()) {
            throw new BreezeApiException("path is required");
        }
        Path importRoot = properties.importRootPath();
        Path candidate = Path.of(requestedPath);
        Path resolved = (candidate.isAbsolute() ? candidate : importRoot.resolve(candidate)).toAbsolutePath().normalize();
        if (!resolved.startsWith(importRoot)) {
            throw new BreezeApiException("Tradebook path must be inside import root " + importRoot);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new BreezeApiException("Tradebook CSV not found at " + resolved);
        }
        return resolved;
    }

    private int requireColumn(Map<String, Integer> columns, String... aliases) {
        int index = findColumn(columns, aliases);
        if (index >= 0) {
            return index;
        }
        throw new BreezeApiException("Zerodha tradebook CSV is missing required column. Tried: " + String.join(", ", aliases));
    }

    private int findColumn(Map<String, Integer> columns, String... aliases) {
        for (String alias : aliases) {
            Integer index = columns.get(normalizeHeader(alias));
            if (index != null) {
                return index;
            }
        }
        return -1;
    }

    private Map<String, Integer> indexColumns(String[] header) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            columns.put(normalizeHeader(header[i]), i);
        }
        return columns;
    }

    private String normalizeAction(String rawAction, double price) {
        String normalized = rawAction == null ? "" : rawAction.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("buy")) {
            return "buy";
        }
        if (normalized.contains("sell")) {
            return "sell";
        }
        if (normalized.contains("bonus")) {
            return "bonus";
        }
        if (normalized.contains("split")) {
            return "split";
        }
        if (normalized.contains("allot")) {
            return "allotment";
        }
        if (normalized.contains("transfer")) {
            return "transfer";
        }
        if (normalized.contains("corporate")) {
            return "corporate_action";
        }
        if (normalized.isBlank() && price == 0) {
            return "zero_price_adjustment";
        }
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private boolean isDerivativeSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            return false;
        }
        String normalized = segment.trim().toUpperCase(Locale.ROOT);
        return DERIVATIVE_SEGMENT_MARKERS.stream().anyMatch(normalized::contains);
    }

    private LocalDate parseDate(String value) {
        String cleaned = clean(value);
        if (cleaned.isBlank()) {
            return null;
        }
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.from(formatter.parse(cleaned));
            } catch (DateTimeParseException ignored) {
            }
        }
        if (cleaned.length() >= 10) {
            String prefix = cleaned.substring(0, 10);
            for (DateTimeFormatter formatter : DATE_FORMATS) {
                try {
                    return LocalDate.from(formatter.parse(prefix));
                } catch (DateTimeParseException ignored) {
                }
            }
        }
        return null;
    }

    private String[] parseCsvLine(String line) {
        return line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed.replace("\"\"", "\"").trim();
    }

    private String cell(String[] values, int index) {
        if (index < 0 || index >= values.length) {
            return "";
        }
        return values[index];
    }

    private String normalizeHeader(String header) {
        return clean(header).toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
    }

    private double parseNumber(String raw) {
        String cleaned = clean(raw).replace(",", "");
        if (cleaned.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private Instant lastModified(Path csvPath) {
        try {
            return Files.getLastModifiedTime(csvPath).toInstant();
        } catch (IOException ex) {
            return Instant.now(clock);
        }
    }

    private String checksum(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private String eventId(String stockCode,
                           LocalDate tradeDate,
                           String action,
                           double quantity,
                           double price,
                           String orderId,
                           String tradeId,
                           String exchange,
                           String segment,
                           String suffix) {
        // The ID is content-based so the same logical event imported from multiple
        // files collapses to one stored record during merge.
        return checksum((stockCode + "|" + tradeDate + "|" + action + "|" + quantity + "|" + price + "|" +
                orderId + "|" + tradeId + "|" + exchange + "|" + segment + "|" + suffix).getBytes(StandardCharsets.UTF_8));
    }

    private String metadataSource(Path csvPath) {
        return csvPath.toAbsolutePath().normalize().toString();
    }

    private long countEvents(List<StoredTradeEvent> events, EventType eventType) {
        return events.stream()
                .filter(event -> event.eventType() == eventType)
                .count();
    }

    private String formatDateRange(LocalDate from, LocalDate to) {
        return from == null || to == null ? "n/a" : from + ".." + to;
    }

    private String formatTradeRange(List<TradeSnapshot> trades) {
        if (trades.isEmpty()) {
            return "n/a";
        }
        LocalDate from = trades.getFirst().tradeDate();
        LocalDate to = trades.getLast().tradeDate();
        return formatDateRange(from, to);
    }

    public record ImportSummary(
            String sourcePath,
            Instant importedAt,
            LocalDate coveredFrom,
            LocalDate coveredTo,
            int importedTrades,
            int storedAdjustments,
            List<String> warnings
    ) {
    }

    public record CoverageSummary(
            boolean hasImports,
            LocalDate coveredFrom,
            LocalDate coveredTo,
            long importedTrades,
            long unresolvedAdjustments,
            List<String> warnings
    ) {
        public boolean covers(LocalDate startInclusive, LocalDate endInclusive) {
            return hasImports
                    && coveredFrom != null
                    && coveredTo != null
                    && !coveredFrom.isAfter(startInclusive)
                    && !coveredTo.isBefore(endInclusive);
        }
    }

    private record CsvParseResult(
            LocalDate coveredFrom,
            LocalDate coveredTo,
            int sourceRows,
            List<StoredTradeEvent> tradeEvents,
            List<StoredTradeEvent> adjustmentEvents,
            List<String> warningMessages
    ) {
    }

    private record StoreState(
            int schemaVersion,
            List<StoreImportMetadata> imports,
            List<StoredTradeEvent> events
    ) {
        private StoreState {
            imports = imports == null ? new ArrayList<>() : imports;
            events = events == null ? new ArrayList<>() : events;
        }
    }

    private record StoreImportMetadata(
            String checksum,
            String sourcePath,
            Instant importedAt,
            Instant fileModifiedAt,
            LocalDate coveredFrom,
            LocalDate coveredTo,
            int sourceRows,
            int importedTrades,
            int storedAdjustments,
            List<String> warnings
    ) {
        private StoreImportMetadata {
            warnings = warnings == null ? List.of() : warnings;
        }
    }

    private record StoredTradeEvent(
            String eventId,
            String sourcePath,
            LocalDate tradeDate,
            String stockCode,
            String action,
            double quantity,
            double price,
            String exchange,
            String segment,
            EventType eventType,
            String specialType,
            boolean requiresManualReview
    ) {
    }

    private enum EventType {
        TRADE,
        ADJUSTMENT
    }
}
