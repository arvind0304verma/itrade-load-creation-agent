package com.hwyhaul.agent.intake.mongo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the intake MongoDB connection that records agent pipeline-run
 * outcomes (see {@link PipelineRunStore}). This is a second, independent Mongo
 * connection alongside {@code agent.omsr.mongo.*}.
 */
@ConfigurationProperties(prefix = "agent.intake.mongo")
public class IntakeMongoProperties {

    private boolean enabled = false;
    private String uri = "";
    private String database = "load_creation_agent";
    private String collection = "pipeline_runs";
    private String usecase = "ITRADE_LOAD_BUILDER";
    private String tenantId = "";

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

    public String getUsecase() {
        return usecase;
    }

    public void setUsecase(String usecase) {
        this.usecase = usecase;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }
}
