package com.hwyhaul.agent.omsr.mongo;

import com.hwyhaul.agent.model.CapturedOrdersPayload;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "agent.omsr.mongo", name = "enabled", havingValue = "true")
public class OmsrProcessedLoadStore {

    private static final Logger log = LoggerFactory.getLogger(OmsrProcessedLoadStore.class);
    static final String PROCESSING_STATUS_FIELD = "processingStatus";
    static final String STATUS_SCRAPED = "SCRAPED";
    static final String STATUS_COMPLETED = "COMPLETED";
    static final String STATUS_FAILED = "FAILED";
    static final String STATUS_SKIPPED = "SKIPPED";

    private final MongoClient mongoClient;
    private final OmsrMongoEventProperties properties;

    public OmsrProcessedLoadStore(MongoClient mongoClient, OmsrMongoEventProperties properties) {
        this.mongoClient = mongoClient;
        this.properties = properties;
    }

    public Set<String> alreadyExtractedLoadNumbers(Collection<String> loadNumbers) {
        if (!properties.isProcessedLoadStoreEnabled() || loadNumbers == null || loadNumbers.isEmpty()) {
            return Set.of();
        }

        List<String> normalizedLoadNumbers = loadNumbers.stream()
                .map(this::normalizeLoadNumber)
                .filter(loadNumber -> loadNumber != null && !loadNumber.isBlank())
                .distinct()
                .toList();
        if (normalizedLoadNumbers.isEmpty()) {
            return Set.of();
        }

        try {
            Set<String> result = new LinkedHashSet<>();
            for (Document document : collection().find(Filters.in("_id", normalizedLoadNumbers))) {
                String loadNumber = document.getString("_id");
                if (loadNumber != null && !loadNumber.isBlank() && shouldSkipProcessedDocument(document)) {
                    result.add(loadNumber);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Unable to read already extracted OMSR load numbers from MongoDB; continuing without skip.", e);
            return Set.of();
        }
    }

    public void markExtracted(String runId, CapturedOrdersPayload.CapturedOrder load) {
        if (!properties.isProcessedLoadStoreEnabled() || load == null) {
            return;
        }

        String loadNumber = normalizeLoadNumber(load.externalOrderId);
        if (loadNumber == null || loadNumber.isBlank()) {
            return;
        }

        Date now = Date.from(Instant.now());
        Bson update = Updates.combine(
                Updates.setOnInsert("loadNumber", loadNumber),
                Updates.setOnInsert("firstExtractedAt", now),
                Updates.set("lastExtractedAt", now),
                Updates.set("lastRunId", runId),
                Updates.set("source", "omsr"),
                Updates.set(PROCESSING_STATUS_FIELD, STATUS_SCRAPED),
                Updates.set("lastProcessingStatusAt", now),
                Updates.set("pageUrl", load.pageUrl),
                Updates.set("customerName", load.customerName),
                Updates.set("shipDate", load.shipDate),
                Updates.set("deliveryDate", load.deliveryDate),
                Updates.set("totalQuantity", load.totalQuantity),
                Updates.set("palletCount", load.palletCount),
                Updates.set("cubeCount", load.cubeCount),
                Updates.set("weight", load.weight),
                Updates.set("caseCount", load.caseCount),
                Updates.set("status", load.status),
                Updates.unset("lastFailureMessage"),
                Updates.unset("lastFailureType"),
                Updates.inc("extractCount", 1)
        );

        try {
            collection().updateOne(
                    Filters.eq("_id", loadNumber),
                    update,
                    new UpdateOptions().upsert(true)
            );
        } catch (Exception e) {
            log.warn("Unable to mark OMSR load {} as extracted in MongoDB.", loadNumber, e);
        }
    }

    public void markCompleted(String runId, String loadNumber, String loadApiResponse) {
        String normalizedLoadNumber = normalizeLoadNumber(loadNumber);
        if (!canWriteProcessedLoad(normalizedLoadNumber)) {
            return;
        }

        Date now = Date.from(Instant.now());
        Bson update = Updates.combine(
                Updates.setOnInsert("loadNumber", normalizedLoadNumber),
                Updates.setOnInsert("firstExtractedAt", now),
                Updates.set(PROCESSING_STATUS_FIELD, STATUS_COMPLETED),
                Updates.set("lastProcessingStatusAt", now),
                Updates.set("lastCompletedAt", now),
                Updates.set("lastRunId", runId),
                Updates.set("source", "omsr"),
                Updates.set("lastLoadApiResponse", loadApiResponse),
                Updates.unset("lastFailureMessage"),
                Updates.unset("lastFailureType"),
                Updates.inc("completedCount", 1)
        );

        try {
            collection().updateOne(
                    Filters.eq("_id", normalizedLoadNumber),
                    update,
                    new UpdateOptions().upsert(true)
            );
        } catch (Exception e) {
            log.warn("Unable to mark OMSR load {} as completed in MongoDB.", normalizedLoadNumber, e);
        }
    }

    public void markFailed(String runId, String loadNumber, Throwable error) {
        String normalizedLoadNumber = normalizeLoadNumber(loadNumber);
        if (!canWriteProcessedLoad(normalizedLoadNumber)) {
            return;
        }

        try {
            Document existing = collection().find(Filters.eq("_id", normalizedLoadNumber)).first();
            if (existing != null && STATUS_COMPLETED.equalsIgnoreCase(existing.getString(PROCESSING_STATUS_FIELD))) {
                return;
            }

            Date now = Date.from(Instant.now());
            Bson update = Updates.combine(
                    Updates.setOnInsert("loadNumber", normalizedLoadNumber),
                    Updates.setOnInsert("firstExtractedAt", now),
                    Updates.set(PROCESSING_STATUS_FIELD, STATUS_FAILED),
                    Updates.set("lastProcessingStatusAt", now),
                    Updates.set("lastFailedAt", now),
                    Updates.set("lastRunId", runId),
                    Updates.set("source", "omsr"),
                    Updates.set("lastFailureType", error == null ? "Unknown" : error.getClass().getName()),
                    Updates.set("lastFailureMessage", errorMessage(error)),
                    Updates.inc("failureCount", 1)
            );

            collection().updateOne(
                    Filters.eq("_id", normalizedLoadNumber),
                    update,
                    new UpdateOptions().upsert(true)
            );
        } catch (Exception e) {
            log.warn("Unable to mark OMSR load {} as failed in MongoDB.", normalizedLoadNumber, e);
        }
    }

    public void markSkipped(String runId, String loadNumber, String reason) {
        String normalizedLoadNumber = normalizeLoadNumber(loadNumber);
        if (!canWriteProcessedLoad(normalizedLoadNumber)) {
            return;
        }

        Date now = Date.from(Instant.now());
        Bson update = Updates.combine(
                Updates.setOnInsert("loadNumber", normalizedLoadNumber),
                Updates.setOnInsert("firstExtractedAt", now),
                Updates.set(PROCESSING_STATUS_FIELD, STATUS_SKIPPED),
                Updates.set("lastProcessingStatusAt", now),
                Updates.set("lastSkippedAt", now),
                Updates.set("lastRunId", runId),
                Updates.set("source", "omsr"),
                Updates.set("lastSkipReason", reason)
        );

        try {
            collection().updateOne(
                    Filters.eq("_id", normalizedLoadNumber),
                    update,
                    new UpdateOptions().upsert(true)
            );
        } catch (Exception e) {
            log.warn("Unable to mark OMSR load {} as skipped in MongoDB.", normalizedLoadNumber, e);
        }
    }

    static boolean shouldSkipProcessedDocument(Document document) {
        if (document == null) {
            return false;
        }
        String processingStatus = document.getString(PROCESSING_STATUS_FIELD);
        return processingStatus == null
                || processingStatus.isBlank()
                || STATUS_COMPLETED.equalsIgnoreCase(processingStatus);
    }

    private MongoCollection<Document> collection() {
        return mongoClient
                .getDatabase(properties.getDatabase())
                .getCollection(properties.getProcessedLoadCollection());
    }

    private boolean canWriteProcessedLoad(String loadNumber) {
        return properties.isProcessedLoadStoreEnabled() && loadNumber != null && !loadNumber.isBlank();
    }

    private String normalizeLoadNumber(String loadNumber) {
        return loadNumber == null ? null : loadNumber.trim();
    }

    private String errorMessage(Throwable error) {
        if (error == null) {
            return "Unknown OMSR load failure.";
        }
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }
}
