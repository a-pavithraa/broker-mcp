package com.broker.reference;

import com.broker.exception.BrokerApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
public class CorporateActionService {

    private static final Logger log = LoggerFactory.getLogger(CorporateActionService.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final TypeReference<List<CorporateActionRecord>> RECORD_LIST = new TypeReference<>() {
    };

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final StockMetadataService stockMetadataService;
    private final String seedPath;
    private final Path storePath;
    private final Clock clock;
    private final Object monitor = new Object();

    @Autowired
    public CorporateActionService(
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper,
            StockMetadataService stockMetadataService,
            @Value("${broker.corporate-actions.seed-path:classpath:stock-corporate-actions.json}") String seedPath,
            @Value("${broker.corporate-actions.store-path:${user.home}/.broker-mcp/stock-corporate-actions.json}") String storePath
    ) {
        this(resourceLoader, objectMapper, stockMetadataService, seedPath, storePath, Clock.system(INDIA));
    }

    public CorporateActionService(
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper,
            StockMetadataService stockMetadataService,
            String seedPath,
            String storePath,
            Clock clock
    ) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.stockMetadataService = stockMetadataService;
        this.seedPath = seedPath;
        this.storePath = Path.of(storePath).toAbsolutePath().normalize();
        this.clock = clock.withZone(INDIA);
    }

    public List<CorporateAction> quantityAdjustmentActionsFor(String stockCode, LocalDate upToDateInclusive) {
        return listActions().stream()
                .filter(CorporateAction::affectsLotQuantity)
                .filter(action -> normalize(stockCode).equals(action.stockCode()))
                .filter(action -> !action.exDate().isAfter(upToDateInclusive))
                .toList();
    }

    public List<CorporateAction> acquisitionActionsFor(String stockCode, LocalDate upToDateInclusive) {
        return listActions().stream()
                .filter(CorporateAction::isAcquisitionHint)
                .filter(action -> normalize(stockCode).equals(action.stockCode()))
                .filter(action -> !action.exDate().isAfter(upToDateInclusive))
                .toList();
    }

    public List<CorporateAction> listActions() {
        synchronized (monitor) {
            return mergedActions();
        }
    }

    public CorporateAction upsertAction(
            String stockCode,
            String type,
            LocalDate exDate,
            double quantityMultiplier,
            String sourceUrl,
            String notes
    ) {
        CorporateAction normalized = validateAndNormalize(stockCode, type, exDate, quantityMultiplier, sourceUrl, notes, Instant.now(clock));
        synchronized (monitor) {
            List<CorporateAction> storeEntries = loadStoreEntries();
            Map<String, CorporateAction> byKey = new LinkedHashMap<>();
            for (CorporateAction entry : storeEntries) {
                byKey.put(entry.uniqueKey(), entry);
            }
            byKey.put(normalized.uniqueKey(), normalized);
            persistStore(byKey.values().stream()
                    .sorted(Comparator.comparing(CorporateAction::stockCode)
                            .thenComparing(CorporateAction::exDate)
                            .thenComparing(CorporateAction::type))
                    .toList());
        }
        return normalized;
    }

    private List<CorporateAction> mergedActions() {
        Map<String, CorporateAction> merged = new LinkedHashMap<>();
        for (CorporateAction action : loadSeedEntries()) {
            merged.put(action.uniqueKey(), action);
        }
        for (CorporateAction action : loadStoreEntries()) {
            merged.put(action.uniqueKey(), action);
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(CorporateAction::stockCode)
                        .thenComparing(CorporateAction::exDate)
                        .thenComparing(CorporateAction::type))
                .toList();
    }

    private List<CorporateAction> loadSeedEntries() {
        Resource resource = resourceLoader.getResource(seedPath);
        if (!resource.exists()) {
            log.info("Corporate action seed file not found at {}; continuing without bundled defaults", seedPath);
            return List.of();
        }
        try (InputStream inputStream = resource.getInputStream()) {
            List<CorporateAction> actions = deserializeRecords(objectMapper.readValue(inputStream, RECORD_LIST), "seed");
            log.info("Loaded {} bundled corporate action entries from {}", actions.size(), seedPath);
            return actions;
        } catch (IOException ex) {
            throw new BrokerApiException("Failed to load corporate action seed data from " + seedPath, ex);
        }
    }

    private List<CorporateAction> loadStoreEntries() {
        if (!Files.exists(storePath)) {
            return List.of();
        }
        try (InputStream inputStream = Files.newInputStream(storePath)) {
            List<CorporateAction> actions = deserializeRecords(objectMapper.readValue(inputStream, RECORD_LIST), "store");
            log.info("Loaded {} external corporate action entries from {}", actions.size(), storePath);
            return actions;
        } catch (IOException ex) {
            throw new BrokerApiException("Failed to read corporate action store from " + storePath, ex);
        }
    }

    private List<CorporateAction> deserializeRecords(List<CorporateActionRecord> records, String sourceLabel) {
        if (records == null) {
            return List.of();
        }
        List<CorporateAction> actions = new ArrayList<>();
        for (CorporateActionRecord record : records) {
            if (record == null) {
                continue;
            }
            actions.add(validateAndNormalize(
                    record.stockCode(),
                    record.type(),
                    record.exDate(),
                    record.quantityMultiplier(),
                    record.sourceUrl(),
                    record.notes(),
                    record.updatedAt() == null ? Instant.EPOCH : record.updatedAt()
            ));
        }
        log.debug("Deserialized {} corporate action entries from {}", actions.size(), sourceLabel);
        return actions;
    }

    private CorporateAction validateAndNormalize(
            String stockCode,
            String type,
            LocalDate exDate,
            double quantityMultiplier,
            String sourceUrl,
            String notes,
            Instant updatedAt
    ) {
        if (stockCode == null || stockCode.isBlank()) {
            throw new BrokerApiException("Corporate action stockCode is required");
        }
        if (exDate == null) {
            throw new BrokerApiException("Corporate action exDate is required for " + stockCode);
        }
        String normalizedType = normalizeType(type);
        double normalizedMultiplier = normalizeMultiplier(normalizedType, quantityMultiplier, stockCode);
        String canonicalCode = stockMetadataService.resolveNseToIcici(stockCode);
        return new CorporateAction(
                normalize(canonicalCode),
                exDate,
                normalizedMultiplier,
                normalizedType,
                blankToNull(sourceUrl),
                blankToNull(notes),
                updatedAt == null ? Instant.EPOCH : updatedAt
        );
    }

    private double normalizeMultiplier(String type, double quantityMultiplier, String stockCode) {
        if ("ALLOTMENT".equals(type)) {
            return quantityMultiplier <= 0 ? 1.0 : quantityMultiplier;
        }
        if (quantityMultiplier <= 0) {
            throw new BrokerApiException("Corporate action quantityMultiplier must be positive for " + stockCode);
        }
        return quantityMultiplier;
    }

    private String normalizeType(String rawType) {
        String normalized = rawType == null ? "" : rawType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SPLIT", "BONUS", "ALLOTMENT" -> normalized;
            default -> throw new BrokerApiException("Unsupported corporate action type: " + rawType);
        };
    }

    private void persistStore(List<CorporateAction> actions) {
        try {
            Path parent = storePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tempFile = Files.createTempFile(parent == null ? Path.of(".") : parent, "corporate-actions", ".json.tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), actions.stream()
                    .map(CorporateActionRecord::from)
                    .toList());
            moveAtomically(tempFile, storePath);
            log.info("Persisted {} corporate action entries to {}", actions.size(), storePath);
        } catch (IOException ex) {
            throw new BrokerApiException("Failed to persist corporate action store to " + storePath, ex);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CorporateAction(
            String stockCode,
            LocalDate exDate,
            double quantityMultiplier,
            String type,
            String sourceUrl,
            String notes,
            Instant updatedAt
    ) {
        public boolean affectsLotQuantity() {
            return "SPLIT".equals(type) || "BONUS".equals(type);
        }

        public boolean isAcquisitionHint() {
            return "ALLOTMENT".equals(type);
        }

        public String uniqueKey() {
            return stockCode + "|" + exDate + "|" + type;
        }
    }

    private record CorporateActionRecord(
            String stockCode,
            LocalDate exDate,
            double quantityMultiplier,
            String type,
            String sourceUrl,
            String notes,
            Instant updatedAt
    ) {
        private static CorporateActionRecord from(CorporateAction action) {
            return new CorporateActionRecord(
                    action.stockCode(),
                    action.exDate(),
                    action.quantityMultiplier(),
                    action.type(),
                    action.sourceUrl(),
                    action.notes(),
                    action.updatedAt()
            );
        }
    }
}
