package com.hwyhaul.agent.omsr.mongo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hwyhaul.agent.omsr.OmsrLoadEvents;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "agent.omsr.mongo",
        name = {"enabled", "event-store-enabled"},
        havingValue = "true"
)
public class OmsrMongoEventStore {

    private static final Logger log = LoggerFactory.getLogger(OmsrMongoEventStore.class);

    private final MongoClient mongoClient;
    private final OmsrMongoEventProperties properties;
    private final OmsrMongoEventDocumentFactory documentFactory;

    public OmsrMongoEventStore(
            MongoClient mongoClient,
            OmsrMongoEventProperties properties,
            ObjectMapper objectMapper
    ) {
        this.mongoClient = mongoClient;
        this.properties = properties;
        this.documentFactory = new OmsrMongoEventDocumentFactory(objectMapper);
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void store(OmsrLoadEvents.FlowStarted event) {
        store("FlowStarted", event.runId(), event);
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void store(OmsrLoadEvents.LoadsCaptured event) {
        store("LoadsCaptured", event.runId(), event);
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void store(OmsrLoadEvents.PayloadsMapped event) {
        store("PayloadsMapped", event.runId(), event);
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void store(OmsrLoadEvents.LoadApiCallsCompleted event) {
        store("LoadApiCallsCompleted", event.runId(), event);
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void store(OmsrLoadEvents.HwyHaulApisSkipped event) {
        store("HwyHaulApisSkipped", event.runId(), event);
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void store(OmsrLoadEvents.FlowSkipped event) {
        store("FlowSkipped", event.runId(), event);
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void store(OmsrLoadEvents.FlowFailed event) {
        store("FlowFailed", event.runId(), documentFactory.flowFailedPayload(event));
    }

    private void store(String eventType, String runId, Object payload) {
        try {
            collection().insertOne(documentFactory.from(eventType, runId, payload));
        } catch (Exception e) {
            log.warn("Unable to store OMSR event {} for run {} in MongoDB.", eventType, runId, e);
        }
    }

    private MongoCollection<Document> collection() {
        return mongoClient
                .getDatabase(properties.getDatabase())
                .getCollection(properties.getEventStoreCollection());
    }
}
