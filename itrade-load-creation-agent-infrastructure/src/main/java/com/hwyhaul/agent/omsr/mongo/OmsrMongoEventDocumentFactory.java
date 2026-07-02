package com.hwyhaul.agent.omsr.mongo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hwyhaul.agent.omsr.OmsrLoadEvents;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

class OmsrMongoEventDocumentFactory {

    private static final String REDACTED = "<redacted>";
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final ObjectMapper objectMapper;

    OmsrMongoEventDocumentFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Document from(String eventType, String runId, Object payload) {
        Document document = new Document("eventType", eventType)
                .append("runId", runId)
                .append("source", "omsr")
                .append("sequence", SEQUENCE.incrementAndGet())
                .append("createdAt", Date.from(Instant.now()))
                .append("payload", payloadDocument(payload));
        return document;
    }

    Object flowFailedPayload(OmsrLoadEvents.FlowFailed event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", event.runId());
        payload.put("error", errorPayload(event.error()));
        return payload;
    }

    private Map<String, Object> errorPayload(Throwable error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (error == null) {
            payload.put("type", "Unknown");
            payload.put("message", "Unknown OMSR load flow failure.");
            payload.put("stackTrace", List.of());
            return payload;
        }

        payload.put("type", error.getClass().getName());
        payload.put("message", error.getMessage());
        payload.put("stackTrace", stackTrace(error));
        return payload;
    }

    private List<String> stackTrace(Throwable error) {
        List<String> stackTrace = new ArrayList<>();
        for (StackTraceElement element : error.getStackTrace()) {
            stackTrace.add(element.toString());
        }
        return stackTrace;
    }

    private Document payloadDocument(Object payload) {
        JsonNode node = objectMapper.valueToTree(payload);
        redact(node);
        try {
            return Document.parse(objectMapper.writeValueAsString(node));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize OMSR event payload.", e);
        }
    }

    private void redact(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return;
        }

        if (node instanceof ObjectNode objectNode) {
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            List<String> sensitiveFields = new ArrayList<>();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitive(field.getKey())) {
                    sensitiveFields.add(field.getKey());
                } else {
                    redact(field.getValue());
                }
            }
            for (String field : sensitiveFields) {
                objectNode.put(field, REDACTED);
            }
            return;
        }

        if (node instanceof ArrayNode arrayNode) {
            for (JsonNode child : arrayNode) {
                redact(child);
            }
        }
    }

    private boolean isSensitive(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        String normalized = fieldName.toLowerCase(Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("authorization")
                || normalized.contains("cookie")
                || normalized.contains("secret")
                || normalized.contains("apikey")
                || normalized.contains("api_key");
    }
}
