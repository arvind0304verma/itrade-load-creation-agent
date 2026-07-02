package com.hwyhaul.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@EnableScheduling
public class OmsrEventConfig {

    @Bean(name = "omsrTaskExecutor")
    public Executor omsrTaskExecutor(
            @Value("${agent.omsr.events.core-pool-size:1}") int corePoolSize,
            @Value("${agent.omsr.events.max-pool-size:2}") int maxPoolSize,
            @Value("${agent.omsr.events.queue-capacity:25}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, corePoolSize));
        executor.setMaxPoolSize(Math.max(Math.max(1, corePoolSize), maxPoolSize));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("omsr-events-");
        executor.initialize();
        return executor;
    }
}
