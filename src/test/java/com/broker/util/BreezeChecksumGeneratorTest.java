package com.broker.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BreezeChecksumGeneratorTest {

    private BreezeChecksumGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new BreezeChecksumGenerator();
    }

    @Test
    void generateTimestamp_shouldReturnValidIsoFormat() {
        String timestamp = generator.generateTimestamp();

        assertNotNull(timestamp);
        assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"),
                "Timestamp should be in ISO8601 format with milliseconds: " + timestamp);
    }

    @Test
    void generateChecksum_shouldReturnSha256Hash() {
        String timestamp = "2024-01-15T10:30:00.000Z";
        String body = "{\"key\":\"value\"}";
        String secretKey = "test_secret_key";

        String checksum = generator.generateChecksum(timestamp, body, secretKey);

        assertNotNull(checksum);
        assertEquals(64, checksum.length(), "SHA-256 hash should be 64 hex characters");
        assertTrue(checksum.matches("[a-f0-9]{64}"), "Checksum should be lowercase hex");
    }

    @Test
    void generateChecksum_shouldBeConsistent() {
        String timestamp = "2024-01-15T10:30:00.000Z";
        String body = "test body";
        String secretKey = "secret123";

        String checksum1 = generator.generateChecksum(timestamp, body, secretKey);
        String checksum2 = generator.generateChecksum(timestamp, body, secretKey);

        assertEquals(checksum1, checksum2, "Same input should produce same checksum");
    }

    @Test
    void generateChecksum_shouldBeDifferentForDifferentInputs() {
        String timestamp = "2024-01-15T10:30:00.000Z";
        String body = "test body";

        String checksum1 = generator.generateChecksum(timestamp, body, "secret1");
        String checksum2 = generator.generateChecksum(timestamp, body, "secret2");

        assertNotEquals(checksum1, checksum2, "Different secrets should produce different checksums");
    }

    @Test
    void generateChecksum_shouldHandleEmptyBody() {
        String timestamp = "2024-01-15T10:30:00.000Z";
        String body = "";
        String secretKey = "test_secret";

        String checksum = generator.generateChecksum(timestamp, body, secretKey);

        assertNotNull(checksum);
        assertEquals(64, checksum.length());
    }

    @Test
    void generateChecksum_shouldMatchExpectedFormat() {
        String checksum = generator.generateChecksum(
                "2024-01-15T10:30:00.000Z",
                "",
                "mysecret"
        );

        assertEquals(64, checksum.length());
        assertTrue(checksum.chars().allMatch(c -> Character.isDigit(c) || (c >= 'a' && c <= 'f')));
    }
}
