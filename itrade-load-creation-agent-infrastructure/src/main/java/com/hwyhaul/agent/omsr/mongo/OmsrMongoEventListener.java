package com.hwyhaul.agent.omsr.mongo;

import com.hwyhaul.agent.omsr.OmsrLoadJobService;
import com.hwyhaul.agent.omsr.OmsrLoadJobStatus;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.client.ChangeStreamIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;
import com.mongodb.client.model.changestream.OperationType;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "agent.omsr.mongo", name = "enabled", havingValue = "true")
public class OmsrMongoEventListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OmsrMongoEventListener.class);
    private static final int MONGO_UNAUTHORIZED_ERROR_CODE = 13;

    private final MongoClient mongoClient;
    private final OmsrMongoEventProperties properties;
    private final OmsrMongoConfigApplier configApplier;
    private final OmsrLoadJobService jobService;
    private final OmsrMongoEventMatcher matcher;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(threadFactory());
    private volatile MongoCursor<ChangeStreamDocument<Document>> cursor;

    public OmsrMongoEventListener(
            MongoClient mongoClient,
            OmsrMongoEventProperties properties,
            OmsrMongoConfigApplier configApplier,
            OmsrLoadJobService jobService
    ) {
        this.mongoClient = mongoClient;
        this.properties = properties;
        this.configApplier = configApplier;
        this.jobService = jobService;
        this.matcher = new OmsrMongoEventMatcher(properties);
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        executor.execute(this::runListener);
    }

    @Override
    public void stop() {
        running.set(false);
        closeCursor();
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("OMSR Mongo event listener did not stop within the expected timeout.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    private void runListener() {
        if (properties.isProcessExistingOnStartup()) {
            processExistingDocuments();
        }
        while (running.get()) {
            watchCollection();
            sleepBeforeRetry();
        }
    }

    private void processExistingDocuments() {
        try {
            log.info("Scanning existing MongoDB OMSR trigger documents in {}.{}.",
                    properties.getDatabase(), properties.getCollection());
            for (Document document : collection().find()) {
                if (!running.get()) {
                    return;
                }
                handleDocument(document, "startup-scan");
            }
        } catch (Exception e) {
            if (running.get()) {
                if (isUnauthorized(e)) {
                    stopForMongoAuthorizationFailure("scan existing trigger documents", "find", e);
                } else {
                    log.warn("Unable to scan existing MongoDB OMSR trigger documents.", e);
                }
            }
        }
    }

    private void watchCollection() {
        try {
            log.info("Watching MongoDB OMSR trigger collection {}.{}.",
                    properties.getDatabase(), properties.getCollection());
            ChangeStreamIterable<Document> stream = collection()
                    .watch()
                    .fullDocument(FullDocument.UPDATE_LOOKUP);
            try (MongoCursor<ChangeStreamDocument<Document>> currentCursor = stream.iterator()) {
                cursor = currentCursor;
                while (running.get() && currentCursor.hasNext()) {
                    handleChange(currentCursor.next());
                }
            } finally {
                cursor = null;
            }
        } catch (MongoInterruptedException e) {
            if (running.get()) {
                log.warn("MongoDB OMSR event listener was interrupted.", e);
            }
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (running.get()) {
                if (isUnauthorized(e)) {
                    stopForMongoAuthorizationFailure("open a change stream", "changeStream and find", e);
                } else {
                    log.warn("MongoDB OMSR event listener disconnected; retrying.", e);
                }
            }
        }
    }

    private void handleChange(ChangeStreamDocument<Document> change) {
        OperationType operationType = change.getOperationType();
        if (operationType != OperationType.INSERT
                && operationType != OperationType.REPLACE
                && operationType != OperationType.UPDATE) {
            return;
        }
        Document document = change.getFullDocument();
        if (document == null) {
            return;
        }
        handleDocument(document, "change-stream");
    }

    private void handleDocument(Document document, String source) {
        if (!matcher.shouldTrigger(document)) {
            return;
        }

        Object documentId = document.get("_id");
        OmsrLoadJobStatus status;
        try {
            configApplier.apply(document);
            status = jobService.triggerLoad();
        } catch (Exception e) {
            log.warn("Failed to dispatch OMSR Mongo event from {} document {}.", source, safeId(documentId), e);
            markDispatchFailed(documentId, e);
            return;
        }

        log.info("Dispatched OMSR Mongo event from {} document {} to job {} run {}.",
                source, safeId(documentId), status.jobId(), status.runId());
        markDispatched(documentId, status);
    }

    private void markDispatched(Object documentId, OmsrLoadJobStatus status) {
        if (!properties.isMarkDispatched() || documentId == null) {
            return;
        }
        List<Bson> updates = new ArrayList<>();
        updates.add(Updates.set(statusField(), "DISPATCHED"));
        updates.add(Updates.set("omsrJobId", status.jobId()));
        updates.add(Updates.set("omsrRunId", status.runId()));
        updates.add(Updates.set("dispatchedAt", Date.from(Instant.now())));
        updateDispatchMarker(documentId, Updates.combine(updates), "mark dispatched trigger documents");
    }

    private void markDispatchFailed(Object documentId, Exception error) {
        if (!properties.isMarkDispatched() || documentId == null) {
            return;
        }
        List<Bson> updates = new ArrayList<>();
        updates.add(Updates.set(statusField(), "DISPATCH_FAILED"));
        updates.add(Updates.set("dispatchError", errorMessage(error)));
        updates.add(Updates.set("dispatchFailedAt", Date.from(Instant.now())));
        updateDispatchMarker(documentId, Updates.combine(updates), "mark failed trigger documents");
    }

    private void updateDispatchMarker(Object documentId, Bson update, String action) {
        try {
            collection().updateOne(Filters.eq("_id", documentId), update);
        } catch (Exception e) {
            if (isUnauthorized(e)) {
                stopForMongoAuthorizationFailure(action, "update, or set agent.omsr.mongo.mark-dispatched=false", e);
            } else {
                log.warn("Unable to {} for MongoDB OMSR document {}.", action, safeId(documentId), e);
            }
        }
    }

    private void stopForMongoAuthorizationFailure(String action, String requiredPrivileges, Throwable error) {
        running.set(false);
        log.error(
                "MongoDB OMSR event listener cannot {} on {}.{} because the configured user is not authorized. "
                        + "Required privilege(s): {}. Fix the MongoDB role grants or disable the listener with "
                        + "agent.omsr.mongo.enabled=false. Listener stopped. MongoDB error: {}",
                action,
                properties.getDatabase(),
                properties.getCollection(),
                requiredPrivileges,
                errorMessage(error)
        );
    }

    static boolean isUnauthorized(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof MongoCommandException commandException
                    && commandException.getErrorCode() == MONGO_UNAUTHORIZED_ERROR_CODE) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String statusField() {
        return StringUtils.hasText(properties.getStatusField()) ? properties.getStatusField() : "eventStatus";
    }

    private MongoCollection<Document> collection() {
        return mongoClient
                .getDatabase(properties.getDatabase())
                .getCollection(properties.getCollection());
    }

    private void sleepBeforeRetry() {
        if (!running.get()) {
            return;
        }
        try {
            Thread.sleep(Math.max(1000, properties.getRetryDelayMs()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeCursor() {
        MongoCursor<ChangeStreamDocument<Document>> currentCursor = cursor;
        if (currentCursor != null) {
            currentCursor.close();
        }
    }

    private String safeId(Object documentId) {
        return documentId == null ? "<missing-id>" : String.valueOf(documentId);
    }

    private String errorMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }

    private ThreadFactory threadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "omsr-mongo-events");
            thread.setDaemon(true);
            return thread;
        };
    }
}
