package com.broker.config;

import com.broker.service.ZerodhaTradebookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Component
@ConditionalOnProperty(name = "zerodha.enabled", havingValue = "true")
public class ZerodhaTradebookStartupImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaTradebookStartupImporter.class);

    private final ZerodhaTradebookProperties properties;
    private final ZerodhaTradebookService zerodhaTradebookService;

    public ZerodhaTradebookStartupImporter(
            ZerodhaTradebookProperties properties,
            ZerodhaTradebookService zerodhaTradebookService) {
        this.properties = properties;
        this.zerodhaTradebookService = zerodhaTradebookService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Path> paths = findTradebookCsvs();
        if (paths.isEmpty()) {
            return;
        }
        for (Path path : paths) {
            try {
                ZerodhaTradebookService.ImportSummary summary = zerodhaTradebookService.importTradebook(path.toString());
                log.info("Imported Zerodha tradebook on startup: path={} coveredFrom={} coveredTo={} trades={} adjustments={} warnings={}",
                        summary.sourcePath(), summary.coveredFrom(), summary.coveredTo(),
                        summary.importedTrades(), summary.storedAdjustments(), summary.warnings().size());
            } catch (Exception ex) {
                log.warn("Zerodha tradebook startup import skipped for '{}': {}",
                        path, ex.getMessage());
            }
        }
    }

    private List<Path> findTradebookCsvs() {
        Path importRoot = properties.importRootPath();
        if (!Files.isDirectory(importRoot)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(importRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    .sorted(Comparator
                            .comparing(this::lastModifiedSafely)
                            .thenComparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException ex) {
            log.warn("Could not scan Zerodha tradebook import root '{}': {}", importRoot, ex.getMessage());
            return List.of();
        }
    }

    private FileTime lastModifiedSafely(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException ex) {
            return FileTime.fromMillis(0);
        }
    }
}
