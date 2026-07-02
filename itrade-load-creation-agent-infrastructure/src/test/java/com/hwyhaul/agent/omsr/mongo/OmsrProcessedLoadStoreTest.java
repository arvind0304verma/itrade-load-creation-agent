package com.hwyhaul.agent.omsr.mongo;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OmsrProcessedLoadStoreTest {

    @Test
    void skipsOnlyCompletedOrLegacyProcessedLoads() {
        assertTrue(OmsrProcessedLoadStore.shouldSkipProcessedDocument(new Document("_id", "ORDER-1")));
        assertTrue(OmsrProcessedLoadStore.shouldSkipProcessedDocument(
                new Document("_id", "ORDER-1")
                        .append(OmsrProcessedLoadStore.PROCESSING_STATUS_FIELD, OmsrProcessedLoadStore.STATUS_COMPLETED)));

        assertFalse(OmsrProcessedLoadStore.shouldSkipProcessedDocument(
                new Document("_id", "ORDER-1")
                        .append(OmsrProcessedLoadStore.PROCESSING_STATUS_FIELD, OmsrProcessedLoadStore.STATUS_SCRAPED)));
        assertFalse(OmsrProcessedLoadStore.shouldSkipProcessedDocument(
                new Document("_id", "ORDER-1")
                        .append(OmsrProcessedLoadStore.PROCESSING_STATUS_FIELD, OmsrProcessedLoadStore.STATUS_FAILED)));
        assertFalse(OmsrProcessedLoadStore.shouldSkipProcessedDocument(
                new Document("_id", "ORDER-1")
                        .append(OmsrProcessedLoadStore.PROCESSING_STATUS_FIELD, OmsrProcessedLoadStore.STATUS_SKIPPED)));
    }
}
