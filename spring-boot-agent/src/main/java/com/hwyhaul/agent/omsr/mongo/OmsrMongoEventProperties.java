package com.hwyhaul.agent.omsr.mongo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "agent.omsr.mongo")
public class OmsrMongoEventProperties {

    private boolean enabled = false;
    private String uri = "";
    private String database = "ai_agent_config";
    private String collection = "3p_rpa_config";
    private String agentId = "omsr";
    private boolean processExistingOnStartup = false;
    private boolean markDispatched = true;
    private String statusField = "eventStatus";
    private boolean eventStoreEnabled = false;
    private String eventStoreCollection = "3p_rpa_event_log";
    private boolean processedLoadStoreEnabled = true;
    private String processedLoadCollection = "3p_rpa_extracted_loads";
    private long retryDelayMs = 5000;
    private List<String> pendingStatuses = List.of("pending", "requested", "new", "ready", "queued");
    private List<String> triggerBooleanFields = List.of(
            "triggerLoad",
            "runNow",
            "startFlow",
            "event.triggerLoad",
            "event.runNow",
            "event.startFlow"
    );
    private List<String> triggerStringFields = List.of(
            "eventType",
            "type",
            "action",
            "event.eventType",
            "event.type",
            "event.action"
    );
    private List<String> triggerTypes = List.of(
            "omsr_load_requested",
            "create_omsr_load",
            "start_omsr_load",
            "run_omsr_load",
            "create_load"
    );
    private List<String> agentFields = List.of(
            "agent",
            "agentId",
            "rpaAgent",
            "source",
            "provider",
            "event.agent",
            "event.agentId"
    );

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public boolean isProcessExistingOnStartup() {
        return processExistingOnStartup;
    }

    public void setProcessExistingOnStartup(boolean processExistingOnStartup) {
        this.processExistingOnStartup = processExistingOnStartup;
    }

    public boolean isMarkDispatched() {
        return markDispatched;
    }

    public void setMarkDispatched(boolean markDispatched) {
        this.markDispatched = markDispatched;
    }

    public String getStatusField() {
        return statusField;
    }

    public void setStatusField(String statusField) {
        this.statusField = statusField;
    }

    public boolean isEventStoreEnabled() {
        return eventStoreEnabled;
    }

    public void setEventStoreEnabled(boolean eventStoreEnabled) {
        this.eventStoreEnabled = eventStoreEnabled;
    }

    public String getEventStoreCollection() {
        return eventStoreCollection;
    }

    public void setEventStoreCollection(String eventStoreCollection) {
        this.eventStoreCollection = eventStoreCollection;
    }

    public boolean isProcessedLoadStoreEnabled() {
        return processedLoadStoreEnabled;
    }

    public void setProcessedLoadStoreEnabled(boolean processedLoadStoreEnabled) {
        this.processedLoadStoreEnabled = processedLoadStoreEnabled;
    }

    public String getProcessedLoadCollection() {
        return processedLoadCollection;
    }

    public void setProcessedLoadCollection(String processedLoadCollection) {
        this.processedLoadCollection = processedLoadCollection;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public void setRetryDelayMs(long retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
    }

    public List<String> getPendingStatuses() {
        return pendingStatuses;
    }

    public void setPendingStatuses(List<String> pendingStatuses) {
        this.pendingStatuses = pendingStatuses;
    }

    public List<String> getTriggerBooleanFields() {
        return triggerBooleanFields;
    }

    public void setTriggerBooleanFields(List<String> triggerBooleanFields) {
        this.triggerBooleanFields = triggerBooleanFields;
    }

    public List<String> getTriggerStringFields() {
        return triggerStringFields;
    }

    public void setTriggerStringFields(List<String> triggerStringFields) {
        this.triggerStringFields = triggerStringFields;
    }

    public List<String> getTriggerTypes() {
        return triggerTypes;
    }

    public void setTriggerTypes(List<String> triggerTypes) {
        this.triggerTypes = triggerTypes;
    }

    public List<String> getAgentFields() {
        return agentFields;
    }

    public void setAgentFields(List<String> agentFields) {
        this.agentFields = agentFields;
    }
}
