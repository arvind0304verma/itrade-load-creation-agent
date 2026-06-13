package com.hwyhaul.agent.omsr.mongo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hwyhaul.agent.model.CapturedOrdersPayload;
import com.hwyhaul.agent.omsr.OmsrLoadEvents;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OmsrMongoEventDocumentFactoryTest {

    private final OmsrMongoEventDocumentFactory factory = new OmsrMongoEventDocumentFactory(new ObjectMapper());

    @Test
    void createsEventDocumentWithJsonPayload() {
        Document document = factory.from("FlowStarted", "run-1", new OmsrLoadEvents.FlowStarted("run-1", true, 4, 10, 3));

        assertEquals("FlowStarted", document.getString("eventType"));
        assertEquals("run-1", document.getString("runId"));
        assertEquals("omsr", document.getString("source"));
        assertNotNull(document.get("createdAt"));
        assertEquals("run-1", document.get("payload", Document.class).getString("runId"));
        assertTrue(document.get("payload", Document.class).getBoolean("skipHwyHaulApis"));
        assertEquals(4, document.get("payload", Document.class).getInteger("detailScrapeConcurrency"));
        assertEquals(10, document.get("payload", Document.class).getInteger("maxLoadsToExtract"));
        assertEquals(3, document.get("payload", Document.class).getInteger("loadApiConcurrency"));
    }

    @Test
    void redactsSensitivePayloadFields() {
        CapturedOrdersPayload capturedPayload = new CapturedOrdersPayload();
        capturedPayload.omsrToken = "omsr-secret";
        capturedPayload.xHhToken = "hwyhaul-secret";
        CapturedOrdersPayload.CapturedOrder order = new CapturedOrdersPayload.CapturedOrder();
        order.externalOrderId = "ORDER-1";
        capturedPayload.loads = List.of(order);

        Document document = factory.from(
                "LoadsCaptured",
                "run-1",
                new OmsrLoadEvents.LoadsCaptured("run-1", capturedPayload)
        );

        Document payload = document.get("payload", Document.class);
        Document storedCapturedPayload = payload.get("capturedPayload", Document.class);
        assertEquals("<redacted>", storedCapturedPayload.getString("omsrToken"));
        assertEquals("<redacted>", storedCapturedPayload.getString("xHhToken"));
        assertEquals("ORDER-1", storedCapturedPayload.getList("loads", Document.class).get(0).getString("externalOrderId"));
    }

    @Test
    void storesFailureDetailsAsJsonPayload() {
        IllegalStateException error = new IllegalStateException("boom");

        Document document = factory.from(
                "FlowFailed",
                "run-1",
                factory.flowFailedPayload(new OmsrLoadEvents.FlowFailed("run-1", error))
        );

        Document errorPayload = document.get("payload", Document.class).get("error", Document.class);
        assertEquals(IllegalStateException.class.getName(), errorPayload.getString("type"));
        assertEquals("boom", errorPayload.getString("message"));
        assertNotNull(errorPayload.getList("stackTrace", String.class));
    }
}
