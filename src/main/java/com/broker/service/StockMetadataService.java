package com.broker.service;

import com.broker.exception.BreezeApiException;
import com.broker.model.AnalysisModels.ResolvedStock;
import com.broker.model.AnalysisModels.StockMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class StockMetadataService {

    private static final Logger log = LoggerFactory.getLogger(StockMetadataService.class);

    private final Map<String, StockMetadata> byCode;
    private final Map<String, StockMetadata> byNseSymbol;
    private final Map<String, StockMetadata> byAlias;

    private final String stockUniversePath;

    public StockMetadataService(
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper,
            @Value("${broker.stock-universe-path:classpath:stock-universe.csv}") String stockUniversePath) {
        this.stockUniversePath = stockUniversePath;
        Map<String, CsvMetadataRow> csvMetadata = loadCsvMetadata(resourceLoader);
        List<StockMetadata> rawMetadata = loadJsonMetadata(resourceLoader, objectMapper);
        this.byCode = new HashMap<>();
        this.byNseSymbol = new HashMap<>();
        this.byAlias = new HashMap<>();

        for (CsvMetadataRow item : csvMetadata.values()) {
            StockMetadata base = csvBackedMetadata(item);
            register(base);
        }

        for (StockMetadata item : rawMetadata) {
            StockMetadata merged = mergeCuratedMetadata(item, csvMetadata.get(normalize(item.code())));
            register(merged);
        }

        long groupedCount = byCode.values().stream().filter(item -> item.group() != null && !item.group().isBlank()).count();
        log.info("Loaded {} stock metadata entries ({} from stock-universe.csv, {} with groups)",
                byCode.size(), csvMetadata.size(), groupedCount);
    }

    public ResolvedStock resolve(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            throw new BreezeApiException("stockCode is required");
        }

        String normalized = normalize(rawInput);
        StockMetadata direct = byCode.get(normalized);
        if (direct != null) {
            return toResolved(direct);
        }

        StockMetadata named = byAlias.get(normalized);
        if (named != null) {
            return toResolved(named);
        }

        Optional<StockMetadata> containsMatch = byAlias.entrySet().stream()
                .filter(entry -> entry.getKey().contains(normalized) || normalized.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();

        if (containsMatch.isPresent()) {
            return toResolved(containsMatch.get());
        }

        return new ResolvedStock(
                rawInput.trim().toUpperCase(Locale.ROOT),
                rawInput.trim(),
                "Other",
                null,
                rawInput.trim().toUpperCase(Locale.ROOT),
                null);
    }

    public StockMetadata getMetadata(String stockCode) {
        StockMetadata fromIndex = byCode.get(normalize(stockCode));
        if (fromIndex != null) {
            return fromIndex;
        }

        return byNseSymbol.get(normalize(stockCode));
    }

    public String resolveNseToIcici(String nseSymbol) {
        if (nseSymbol == null || nseSymbol.isBlank()) {
            return nseSymbol;
        }
        StockMetadata metadata = byNseSymbol.get(normalize(nseSymbol));
        return metadata == null ? nseSymbol.trim().toUpperCase(Locale.ROOT) : metadata.code();
    }

    public String resolveIciciToNse(String iciciCode) {
        if (iciciCode == null || iciciCode.isBlank()) {
            return iciciCode;
        }
        StockMetadata metadata = byCode.get(normalize(iciciCode));
        if (metadata == null) {
            return iciciCode.trim().toUpperCase(Locale.ROOT);
        }
        return metadata.nseSymbol() == null || metadata.nseSymbol().isBlank()
                ? metadata.code()
                : metadata.nseSymbol();
    }

    public String equityJoinKey(String stockCode, String isin) {
        if (isin != null && !isin.isBlank()) {
            return isin.trim().toUpperCase(Locale.ROOT);
        }
        if (stockCode == null || stockCode.isBlank()) {
            return "";
        }
        StockMetadata metadata = getMetadata(stockCode);
        return metadata == null ? stockCode.trim().toUpperCase(Locale.ROOT) : metadata.code();
    }

    private void register(StockMetadata metadata) {
        byCode.put(normalize(metadata.code()), metadata);
        if (metadata.nseSymbol() != null && !metadata.nseSymbol().isBlank()) {
            byNseSymbol.put(normalize(metadata.nseSymbol()), metadata);
        }
        if (metadata.name() != null && !metadata.name().isBlank()) {
            byAlias.put(normalize(metadata.name()), metadata);
        }
        if (metadata.aliases() != null) {
            for (String alias : metadata.aliases()) {
                if (alias != null && !alias.isBlank()) {
                    byAlias.put(normalize(alias), metadata);
                }
            }
        }
    }

    private StockMetadata mergeCuratedMetadata(StockMetadata curated, CsvMetadataRow csvRow) {
        String sector = csvRow == null
                ? firstNonBlank(curated.sector(), "Other")
                : firstNonBlank(blankToNull(csvRow.industry()), "Other");
        String name = firstNonBlank(curated.name(), csvRow == null ? null : csvRow.companyName(), curated.code());
        String group = csvRow == null ? blankToNull(curated.group()) : blankToNull(csvRow.group());
        String nseSymbol = csvRow == null
                ? firstNonBlank(blankToNull(curated.nseSymbol()), curated.code())
                : firstNonBlank(blankToNull(csvRow.nseSymbol()), blankToNull(curated.nseSymbol()), curated.code());
        String isin = csvRow == null
                ? blankToNull(curated.isin())
                : firstNonBlank(blankToNull(csvRow.isin()), blankToNull(curated.isin()));
        return new StockMetadata(curated.code(), name, sector, group, nseSymbol, isin, curated.aliases());
    }

    private StockMetadata csvBackedMetadata(CsvMetadataRow csvRow) {
        String sector = firstNonBlank(
                csvRow.industry(),
                "Other"
        );
        return new StockMetadata(
                csvRow.code(),
                firstNonBlank(csvRow.companyName(), csvRow.code()),
                sector,
                blankToNull(csvRow.group()),
                firstNonBlank(blankToNull(csvRow.nseSymbol()), csvRow.code()),
                blankToNull(csvRow.isin()),
                List.of()
        );
    }

    private Map<String, CsvMetadataRow> loadCsvMetadata(ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource(stockUniversePath);
        if (!resource.exists()) {
            log.info("stock-universe.csv not found at {}; falling back to curated metadata only", stockUniversePath);
            return Map.of();
        }
        log.info("Loading stock-universe.csv from {}", stockUniversePath);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return Map.of();
            }

            String[] header = parseCsvLine(headerLine);
            Map<String, Integer> columns = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                columns.put(clean(header[i]).toLowerCase(Locale.ROOT), i);
            }

            Integer codeIndex = columns.get("icici_code");
            Integer nseSymbolIndex = columns.get("nse_symbol");
            Integer companyIndex = columns.get("company_name");
            Integer isinIndex = columns.get("isin_code");
            Integer industryIndex = columns.get("industry");
            Integer groupIndex = columns.get("group");
            if (codeIndex == null || companyIndex == null || industryIndex == null) {
                log.warn("stock-universe.csv is missing required columns; ignoring it");
                return Map.of();
            }

            Map<String, CsvMetadataRow> result = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = parseCsvLine(line);
                String code = normalize(cell(values, codeIndex));
                if (code.isBlank()) {
                    continue;
                }
                result.put(code, new CsvMetadataRow(
                        code,
                        nseSymbolIndex == null ? null : clean(cell(values, nseSymbolIndex)),
                        clean(cell(values, companyIndex)),
                        isinIndex == null ? null : clean(cell(values, isinIndex)),
                        clean(cell(values, industryIndex)),
                        groupIndex == null ? null : clean(cell(values, groupIndex))
                ));
            }
            return result;
        } catch (IOException e) {
            throw new BreezeApiException("Failed to load stock-universe.csv", e);
        }
    }

    private List<StockMetadata> loadJsonMetadata(ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        Resource resource = resourceLoader.getResource("classpath:stock-metadata.json");
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new BreezeApiException("Failed to load stock metadata", e);
        }
    }

    private ResolvedStock toResolved(StockMetadata metadata) {
        return new ResolvedStock(
                metadata.code(),
                metadata.name(),
                metadata.sector(),
                metadata.group(),
                firstNonBlank(blankToNull(metadata.nseSymbol()), metadata.code()),
                blankToNull(metadata.isin())
        );
    }

    private String[] parseCsvLine(String line) {
        return line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
    }

    private String cell(String[] values, int index) {
        if (index < 0 || index >= values.length) {
            return "";
        }
        return values[index];
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record CsvMetadataRow(
            String code,
            String nseSymbol,
            String companyName,
            String isin,
            String industry,
            String group
    ) {
    }
}
