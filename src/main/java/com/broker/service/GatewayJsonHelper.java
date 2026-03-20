package com.broker.service;

import tools.jackson.databind.JsonNode;

final class GatewayJsonHelper {

    private GatewayJsonHelper() {
    }

    static String text(JsonNode node, String field) {
        return text(node, field, "");
    }

    static String text(JsonNode node, String field, String fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        return value.asText();
    }

    static double number(JsonNode node, String field) {
        return number(node, new String[]{field});
    }

    static double number(JsonNode node, String... fields) {
        for (String field : fields) {
            String raw = text(node, field, "");
            if (raw.isBlank()) {
                continue;
            }
            try {
                return Double.parseDouble(raw.replace(",", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
