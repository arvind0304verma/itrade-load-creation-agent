package com.hwyhaul.agent.omsr.mongo;

import org.bson.Document;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

final class OmsrMongoDocumentReader {

    private OmsrMongoDocumentReader() {
    }

    static Object value(Document document, String path) {
        if (document == null || path == null || path.isBlank()) {
            return null;
        }
        if (document.containsKey(path)) {
            return document.get(path);
        }
        Object current = document;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    static String string(Document document, String... paths) {
        for (String path : paths) {
            Object value = value(document, path);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    static Boolean bool(Document document, String... paths) {
        for (String path : paths) {
            Object value = value(document, path);
            if (value instanceof Boolean bool) {
                return bool;
            }
            if (value != null) {
                String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
                if ("true".equals(text) || "yes".equals(text) || "1".equals(text)) {
                    return true;
                }
                if ("false".equals(text) || "no".equals(text) || "0".equals(text)) {
                    return false;
                }
            }
        }
        return null;
    }

    static Integer integer(Document document, String... paths) {
        for (String path : paths) {
            Object value = value(document, path);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value != null) {
                try {
                    return Integer.parseInt(String.valueOf(value).trim());
                } catch (NumberFormatException ignored) {
                    // Try the next candidate path.
                }
            }
        }
        return null;
    }

    static BigDecimal decimal(Document document, String... paths) {
        for (String path : paths) {
            Object value = value(document, path);
            if (value instanceof BigDecimal decimal) {
                return decimal;
            }
            if (value instanceof Number number) {
                return BigDecimal.valueOf(number.doubleValue());
            }
            if (value != null) {
                try {
                    return new BigDecimal(String.valueOf(value).trim());
                } catch (NumberFormatException ignored) {
                    // Try the next candidate path.
                }
            }
        }
        return null;
    }
}
