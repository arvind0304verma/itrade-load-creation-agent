package com.hwyhaul.agent.omsr.mongo;

import org.bson.Document;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

class OmsrMongoEventMatcher {

    private final OmsrMongoEventProperties properties;

    OmsrMongoEventMatcher(OmsrMongoEventProperties properties) {
        this.properties = properties;
    }

    boolean shouldTrigger(Document document) {
        if (document == null || explicitlyDisabled(document)) {
            return false;
        }
        if (!statusIsPending(document)) {
            return false;
        }
        if (!agentMatches(document)) {
            return false;
        }
        return hasTriggerFlag(document) || hasTriggerType(document);
    }

    private boolean explicitlyDisabled(Document document) {
        Boolean enabled = OmsrMongoDocumentReader.bool(
                document,
                "enabled",
                "active",
                "agent.enabled",
                "event.enabled"
        );
        return enabled != null && !enabled;
    }

    private boolean statusIsPending(Document document) {
        String status = OmsrMongoDocumentReader.string(document, statusFields());
        if (!StringUtils.hasText(status)) {
            return true;
        }
        return pendingStatuses().contains(normalize(status));
    }

    private String[] statusFields() {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        if (StringUtils.hasText(properties.getStatusField())) {
            fields.add(properties.getStatusField());
        }
        fields.add("eventStatus");
        fields.add("status");
        fields.add("event.status");
        return fields.toArray(String[]::new);
    }

    private boolean agentMatches(Document document) {
        String expectedAgent = normalize(properties.getAgentId());
        if (!StringUtils.hasText(expectedAgent)) {
            return true;
        }

        boolean sawAgent = false;
        for (String field : properties.getAgentFields()) {
            String actualAgent = OmsrMongoDocumentReader.string(document, field);
            if (!StringUtils.hasText(actualAgent)) {
                continue;
            }
            sawAgent = true;
            if (expectedAgent.equals(normalize(actualAgent))) {
                return true;
            }
        }
        return !sawAgent;
    }

    private boolean hasTriggerFlag(Document document) {
        for (String field : properties.getTriggerBooleanFields()) {
            Boolean flag = OmsrMongoDocumentReader.bool(document, field);
            if (flag != null && flag) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTriggerType(Document document) {
        Set<String> triggerTypes = triggerTypes();
        for (String field : properties.getTriggerStringFields()) {
            String eventType = OmsrMongoDocumentReader.string(document, field);
            if (StringUtils.hasText(eventType) && triggerTypes.contains(normalize(eventType))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> pendingStatuses() {
        Set<String> statuses = new LinkedHashSet<>();
        for (String status : properties.getPendingStatuses()) {
            statuses.add(normalize(status));
        }
        return statuses;
    }

    private Set<String> triggerTypes() {
        Set<String> types = new LinkedHashSet<>();
        for (String type : properties.getTriggerTypes()) {
            types.add(normalize(type));
        }
        return types;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
    }
}
