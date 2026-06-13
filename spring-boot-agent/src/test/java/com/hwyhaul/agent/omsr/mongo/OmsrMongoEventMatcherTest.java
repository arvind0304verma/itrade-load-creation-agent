package com.hwyhaul.agent.omsr.mongo;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OmsrMongoEventMatcherTest {

    private final OmsrMongoEventMatcher matcher = new OmsrMongoEventMatcher(new OmsrMongoEventProperties());

    @Test
    void triggersPendingOmsrLoadEvent() {
        Document document = new Document("agentId", "OMSR")
                .append("eventStatus", "PENDING")
                .append("eventType", "OMSR_LOAD_REQUESTED")
                .append("enabled", true);

        assertTrue(matcher.shouldTrigger(document));
    }

    @Test
    void triggersNestedRunNowEvent() {
        Document document = new Document("agent", "omsr")
                .append("status", "requested")
                .append("event", new Document("runNow", true));

        assertTrue(matcher.shouldTrigger(document));
    }

    @Test
    void ignoresAlreadyDispatchedEvent() {
        Document document = new Document("agentId", "omsr")
                .append("eventStatus", "DISPATCHED")
                .append("triggerLoad", true);

        assertFalse(matcher.shouldTrigger(document));
    }

    @Test
    void ignoresWrongAgentEvent() {
        Document document = new Document("agentId", "other-agent")
                .append("eventStatus", "PENDING")
                .append("eventType", "OMSR_LOAD_REQUESTED");

        assertFalse(matcher.shouldTrigger(document));
    }

    @Test
    void ignoresDisabledEvent() {
        Document document = new Document("agentId", "omsr")
                .append("eventStatus", "PENDING")
                .append("eventType", "OMSR_LOAD_REQUESTED")
                .append("enabled", false);

        assertFalse(matcher.shouldTrigger(document));
    }
}
