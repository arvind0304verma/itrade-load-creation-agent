package com.hwyhaul.agent.intake.mongo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Records the final state of each load pipeline run in the intake MongoDB
 * {@code pipeline_runs} collection. Each document is keyed by
 * {@code loadNumber} and transitions PROCESSING -&gt; COMPLETED/FAILED.
 *
 * <p>All writes are best-effort: a MongoDB failure is logged and swallowed so it never
 * breaks the load-creation flow.
 */
@Component
@ConditionalOnProperty(prefix = "agent.intake.mongo", name = "enabled", havingValue = "true")
public class PipelineRunStore {

    private static final Logger log = LoggerFactory.getLogger(PipelineRunStore.class);

    static final String STATUS_PROCESSING = "PROCESSING";
    static final String STATUS_COMPLETED = "COMPLETED";
    static final String STATUS_FAILED = "FAILED";

    private final MongoClient mongoClient;
    private final IntakeMongoProperties properties;
    private final ObjectMapper mapper;

    public PipelineRunStore(
            @Qualifier("intakeMongoClient") MongoClient mongoClient,
            IntakeMongoProperties properties,
            ObjectMapper mapper
    ) {
        this.mongoClient = mongoClient;
        this.properties = properties;
        this.mapper = mapper;
    }

    /** Marks a load as being processed (upserts the run document in PROCESSING state). */
    public void markProcessing(String customerLoadNumber) {
        upsert(customerLoadNumber, STATUS_PROCESSING, null, null);
    }

    /** Marks a load as completed, recording the created load id parsed from the Load API response. */
    public void markCompleted(String customerLoadNumber, String loadApiResponse) {
        upsert(customerLoadNumber, STATUS_COMPLETED, extractLoadId(mapper, loadApiResponse), null);
    }

    /** Marks a load as failed, recording a descriptive failure reason. */
    public void markFailed(String customerLoadNumber, Throwable error) {
        upsert(customerLoadNumber, STATUS_FAILED, null, failReason(error));
    }

    private void upsert(String customerLoadNumber, String status, String loadId, String failReason) {
        if (customerLoadNumber == null || customerLoadNumber.isBlank()) {
            return;
        }

        Date now = Date.from(Instant.now());
        List<Bson> updates = new ArrayList<>();
        updates.add(Updates.setOnInsert("usecase", properties.getUsecase()));
        updates.add(Updates.setOnInsert("tenantId", emptyToNull(properties.getTenantId())));
        updates.add(Updates.setOnInsert("loadNumber", customerLoadNumber));
        updates.add(Updates.setOnInsert("createdAt", now));
        updates.add(Updates.set("status", status));
        updates.add(Updates.set("updatedAt", now));
        if (loadId != null && !loadId.isBlank()) {
            updates.add(Updates.set("loadId", loadId));
        }
        if (failReason != null && !failReason.isBlank()) {
            updates.add(Updates.set("failReason", failReason));
        } else {
            updates.add(Updates.unset("failReason"));
        }

        try {
            collection().updateOne(
                    Filters.eq("loadNumber", customerLoadNumber),
                    Updates.combine(updates),
                    new UpdateOptions().upsert(true)
            );
        } catch (Exception e) {
            log.warn("Unable to write pipeline run for customerLoadNumber={} status={} to intake MongoDB.",
                    customerLoadNumber, status, e);
        }
    }

    private MongoCollection<Document> collection() {
        return mongoClient
                .getDatabase(properties.getDatabase())
                .getCollection(properties.getCollection());
    }

    /**
     * Extracts the created load id from a Load API response body, tolerant of the response
     * being plain text (e.g. a skip message) or a nested/array JSON shape. Returns {@code null}
     * when no load-identifying value is found.
     */
    static String extractLoadId(ObjectMapper mapper, String loadApiResponse) {
        if (loadApiResponse == null || loadApiResponse.isBlank()) {
            return null;
        }
        try {
            return idFromNode(mapper.readTree(loadApiResponse));
        } catch (Exception e) {
            return null;
        }
    }

    private static String idFromNode(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return null;
        }

        JsonNode data = root.has("data") ? root.path("data") : root;
        if (data.isValueNode()) {
            String value = data.asText(null);
            return value != null && !value.isBlank() && !"null".equalsIgnoreCase(value) ? value : null;
        }
        if (data.isArray()) {
            for (JsonNode item : data) {
                String id = idFromObject(item);
                if (id != null) {
                    return id;
                }
            }
            return null;
        }
        return idFromObject(data);
    }

    private static String idFromObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String key : new String[]{"id", "loadId", "loadNumber", "loadNo", "referenceNumber"}) {
            String value = node.path(key).asText(null);
            if (value != null && !value.isBlank() && !"null".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static final int MAX_FAIL_REASON_LENGTH = 500;

    /**
     * Markers that begin the verbose diagnostic tails {@link com.hwyhaul.agent.loadapi.LoadApiClient}
     * appends to a failure message (payload dumps, omitted-field lists, boilerplate). Everything from
     * the earliest marker onward is dropped so {@code failReason} stays a short, human-readable summary.
     */
    private static final String[] VERBOSE_TAIL_MARKERS = {
            ". Null or omitted payload fields:",
            ". Payload sent:",
            ". Verify the resolved company"
    };

    private static String failReason(Throwable error) {
        if (error == null) {
            return "Unknown load pipeline failure.";
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return summarizeFailReason(message);
    }

    /**
     * Reduces a raw failure message to a concise, human-readable reason: strips the verbose
     * diagnostic tails and caps the length. The HwyHaul HTTP status and response body, which
     * carry the actual reason, are preserved.
     */
    static String summarizeFailReason(String message) {
        String summary = message.trim();
        int cutAt = summary.length();
        for (String marker : VERBOSE_TAIL_MARKERS) {
            int idx = summary.indexOf(marker);
            if (idx >= 0 && idx < cutAt) {
                cutAt = idx;
            }
        }
        summary = summary.substring(0, cutAt).trim();
        if (summary.length() > MAX_FAIL_REASON_LENGTH) {
            summary = summary.substring(0, MAX_FAIL_REASON_LENGTH).trim() + "…";
        }
        return summary.isBlank() ? message.trim() : summary;
    }
}
