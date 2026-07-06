package com.hwyhaul.agent.omsr;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Quartz job that fires the OMSR load-creation agent on a fixed cadence.
 * The overlap and enabled guards live in {@link OmsrLoadJobService#triggerScheduledLoad()};
 * {@link DisallowConcurrentExecution} additionally prevents the job itself from re-entering
 * while a previous fire is still executing.
 */
@DisallowConcurrentExecution
public class OmsrLoadQuartzJob extends QuartzJobBean {

    @Autowired
    private OmsrLoadJobService jobService;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        jobService.triggerScheduledLoad();
    }
}
