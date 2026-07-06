package com.hwyhaul.agent.intake.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Creates the intake MongoDB client used to record pipeline-run outcomes. This is a
 * separate {@link MongoClient} from the OMSR one; components that need it inject it with
 * {@code @Qualifier("intakeMongoClient")}. The OMSR client remains {@code @Primary} so
 * unqualified {@link MongoClient} injections keep resolving to it.
 */
@Configuration
@ConditionalOnProperty(prefix = "agent.intake.mongo", name = "enabled", havingValue = "true")
public class IntakeMongoClientConfig {

    @Bean(name = "intakeMongoClient", destroyMethod = "close")
    @ConditionalOnMissingBean(name = "intakeMongoClient")
    public MongoClient intakeMongoClient(IntakeMongoProperties properties) {
        if (!StringUtils.hasText(properties.getUri())) {
            throw new IllegalStateException("Missing MongoDB connection string. Set agent.intake.mongo.uri.");
        }
        return MongoClients.create(properties.getUri());
    }
}
