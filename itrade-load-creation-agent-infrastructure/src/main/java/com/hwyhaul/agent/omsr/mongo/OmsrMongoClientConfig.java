package com.hwyhaul.agent.omsr.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

@Configuration
@ConditionalOnProperty(prefix = "agent.omsr.mongo", name = "enabled", havingValue = "true")
public class OmsrMongoClientConfig {

    // @Primary + name-scoped condition so a second MongoClient bean (e.g. the intake client)
    // never suppresses this one and unqualified MongoClient injections still resolve here.
    @Bean(name = "omsrMongoClient", destroyMethod = "close")
    @Primary
    @ConditionalOnMissingBean(name = "omsrMongoClient")
    public MongoClient omsrMongoClient(OmsrMongoEventProperties properties) {
        if (!StringUtils.hasText(properties.getUri())) {
            throw new IllegalStateException("Missing MongoDB connection string. Set agent.omsr.mongo.uri.");
        }
        return MongoClients.create(properties.getUri());
    }
}
