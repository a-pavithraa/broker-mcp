package com.broker.gateway.zerodha;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ZerodhaInstrumentCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void initialize_shouldUseFreshCacheWithoutRedownloading() throws Exception {
        writeCache(csv(
                row("256265", "NIFTY 50", "NSE"),
                row("408065", "INFY", "NSE")
        ), "2026-03-16T05:40:00Z");

        AtomicInteger downloads = new AtomicInteger();
        ZerodhaInstrumentCache cache = new ZerodhaInstrumentCache(
                tempDir,
                fixedIndiaClock("2026-03-16T11:00:00+05:30"),
                exchange -> {
                    downloads.incrementAndGet();
                    return csv();
                });

        cache.initialize();

        assertEquals(0, downloads.get());
        assertEquals(408065L, cache.getInstrumentToken("NSE", "INFY"));
        assertEquals("NSE:NIFTY 50", cache.resolveQuoteInstrument("NSE", "NIFTY"));
    }

    @Test
    void initialize_shouldRefreshStaleCacheAfterSixAmBoundary() throws Exception {
        writeCache(csv(row("408065", "INFY", "NSE")), "2026-03-15T00:10:00Z");

        AtomicInteger downloads = new AtomicInteger();
        ZerodhaInstrumentCache cache = new ZerodhaInstrumentCache(
                tempDir,
                fixedIndiaClock("2026-03-16T11:00:00+05:30"),
                exchange -> {
                    downloads.incrementAndGet();
                    return switch (exchange) {
                        case "NSE" -> csv(row("256265", "NIFTY 50", "NSE"), row("1594", "INFY", "NSE"));
                        case "BSE" -> csv(row("500325", "RELIANCE", "BSE"));
                        case "NFO" -> csv(row("123456", "NIFTY24MARFUT", "NFO"));
                        default -> csv();
                    };
                });

        cache.initialize();

        assertEquals(3, downloads.get());
        assertEquals(1594L, cache.getInstrumentToken("NSE", "INFY"));
        assertEquals(123456L, cache.getInstrumentToken("NFO", "NIFTY24MARFUT"));
        String meta = Files.readString(tempDir.resolve("zerodha-instruments.meta"), StandardCharsets.UTF_8).trim();
        assertFalse(meta.isBlank());
    }

    @Test
    void initialize_shouldFallBackToStaleCacheWhenDownloadFails() throws Exception {
        writeCache(csv(row("408065", "INFY", "NSE")), "2026-03-15T00:10:00Z");

        ZerodhaInstrumentCache cache = new ZerodhaInstrumentCache(
                tempDir,
                fixedIndiaClock("2026-03-16T11:00:00+05:30"),
                exchange -> {
                    throw new IOException("network down");
                });

        cache.initialize();

        assertEquals(408065L, cache.getInstrumentToken("NSE", "INFY"));
    }

    @Test
    void resolveQuoteInstrument_shouldUseHardcodedIndexMappings() {
        ZerodhaInstrumentCache cache = new ZerodhaInstrumentCache(
                tempDir,
                fixedIndiaClock("2026-03-16T11:00:00+05:30"),
                exchange -> csv());

        assertEquals("NSE:NIFTY 50", cache.resolveQuoteInstrument("NSE", "NIFTY"));
        assertEquals("NSE:NIFTY BANK", cache.resolveQuoteInstrument("NSE", "CNXBAN"));
        assertEquals("NSE:NIFTY MIDCAP 100", cache.resolveQuoteInstrument("NSE", "NIFTYMIDCAP"));
    }

    @Test
    void findOptionContracts_shouldReturnExpiryAndRightFilteredContracts() {
        ZerodhaInstrumentCache cache = new ZerodhaInstrumentCache(
                tempDir,
                fixedIndiaClock("2026-03-16T11:00:00+05:30"),
                exchange -> switch (exchange) {
                    case "NSE", "BSE" -> csv();
                    case "NFO" -> """
                            instrument_token,tradingsymbol,exchange,name,expiry,strike,instrument_type
                            101,NIFTY24MAR22500CE,NFO,NIFTY,2026-03-19,22500,CE
                            102,NIFTY24MAR22600CE,NFO,NIFTY,2026-03-19,22600,CE
                            103,NIFTY24MAR22500PE,NFO,NIFTY,2026-03-19,22500,PE
                            """;
                    default -> csv();
                });

        cache.initialize();

        var calls = cache.findOptionContracts("NIFTY", LocalDate.of(2026, 3, 19), "call");
        var puts = cache.findOptionContracts("NIFTY", LocalDate.of(2026, 3, 19), "put");

        assertEquals(2, calls.size());
        assertEquals(1, puts.size());
        assertEquals("NFO:NIFTY24MAR22500CE", calls.getFirst().quoteInstrument());
        assertEquals(22500.0, puts.getFirst().strikePrice());
    }

    @Test
    void findInstrumentToken_shouldReturnEmptyWhenCacheCannotBeBuilt() {
        ZerodhaInstrumentCache cache = new ZerodhaInstrumentCache(
                tempDir,
                fixedIndiaClock("2026-03-16T11:00:00+05:30"),
                exchange -> {
                    throw new IOException("no session");
                });

        OptionalLong token = cache.findInstrumentToken("NSE", "INFY");

        assertTrue(token.isEmpty());
    }

    private void writeCache(String csv, String fetchedAt) throws Exception {
        Files.writeString(tempDir.resolve("zerodha-instruments.cache"), csv, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("zerodha-instruments.meta"), fetchedAt, StandardCharsets.UTF_8);
    }

    private String csv(String... rows) {
        StringBuilder builder = new StringBuilder("instrument_token,tradingsymbol,exchange,name,expiry,strike,instrument_type\n");
        for (String row : rows) {
            builder.append(row).append('\n');
        }
        return builder.toString();
    }

    private String row(String token, String symbol, String exchange) {
        return token + "," + symbol + "," + exchange + ",,,," ;
    }

    private Clock fixedIndiaClock(String isoOffsetDateTime) {
        return Clock.fixed(
                Instant.parse(ZonedDateTime.parse(isoOffsetDateTime).toInstant().toString()),
                ZoneId.of("Asia/Kolkata"));
    }
}
