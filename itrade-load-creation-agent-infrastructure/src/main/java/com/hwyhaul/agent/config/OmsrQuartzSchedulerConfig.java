package com.hwyhaul.agent.config;

import com.hwyhaul.agent.omsr.OmsrLoadQuartzJob;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.Date;

/**
 * Registers the Quartz {@link JobDetail} and {@link Trigger} that run the OMSR load-creation
 * agent every {@code agent.omsr.job.interval-ms} (15 minutes by default). Spring Boot's Quartz
 * auto-configuration picks up these beans and schedules them on the in-memory scheduler.
 *
 * <p>Only active when {@code agent.omsr.job.enabled=true}, preserving the previous opt-in behaviour.
 */
@Configuration
@ConditionalOnProperty(prefix = "agent.omsr.job", name = "enabled", havingValue = "true")
public class OmsrQuartzSchedulerConfig {

    static final String JOB_NAME = "omsrLoadJob";
    static final String TRIGGER_NAME = "omsrLoadJobTrigger";

    @Bean
    public JobDetail omsrLoadJobDetail() {
        return JobBuilder.newJob(OmsrLoadQuartzJob.class)
                .withIdentity(JOB_NAME)
                .withDescription("Scheduled OMSR load-creation agent run")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger omsrLoadJobTrigger(
            JobDetail omsrLoadJobDetail,
            @Value("${agent.omsr.job.interval-ms:900000}") long intervalMs,
            @Value("${agent.omsr.job.initial-delay-ms:30000}") long initialDelayMs
    ) {
        SimpleScheduleBuilder schedule = SimpleScheduleBuilder.simpleSchedule()
                .withIntervalInMilliseconds(intervalMs)
                .repeatForever();

        return TriggerBuilder.newTrigger()
                .forJob(omsrLoadJobDetail)
                .withIdentity(TRIGGER_NAME)
                .withDescription("Fires the OMSR load-creation agent every " + intervalMs + " ms")
                .startAt(Date.from(Instant.now().plusMillis(Math.max(0, initialDelayMs))))
                .withSchedule(schedule)
                .build();
    }
}
