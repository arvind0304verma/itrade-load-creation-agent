package com.hwyhaul.agent.omsr;

import com.hwyhaul.agent.agent.AgentContext;
import com.hwyhaul.agent.agent.AgentState;
import com.hwyhaul.agent.agent.OmsrLoadAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class OmsrLoadJobService {

    private static final Logger log = LoggerFactory.getLogger(OmsrLoadJobService.class);

    private final OmsrLoadAgentService agentService;
    private final ConcurrentMap<String, JobRecord> jobs = new ConcurrentHashMap<>();
    private final AtomicReference<String> latestJobId = new AtomicReference<>();
    private final boolean scheduledEnabled;

    public OmsrLoadJobService(
            OmsrLoadAgentService agentService,
            @Value("${agent.omsr.job.enabled:false}") boolean scheduledEnabled
    ) {
        this.agentService = agentService;
        this.scheduledEnabled = scheduledEnabled;
    }

    public OmsrLoadJobStatus triggerLoad() {
        return triggerLoad(
                false,
                OmsrLoadFlowCoordinator.DEFAULT_DETAIL_SCRAPE_CONCURRENCY,
                OmsrLoadFlowCoordinator.DEFAULT_MAX_LOADS_TO_EXTRACT,
                OmsrLoadFlowCoordinator.DEFAULT_LOAD_API_CONCURRENCY);
    }

    public OmsrLoadJobStatus triggerLoad(boolean skipHwyHaulApis) {
        return triggerLoad(
                skipHwyHaulApis,
                OmsrLoadFlowCoordinator.DEFAULT_DETAIL_SCRAPE_CONCURRENCY,
                OmsrLoadFlowCoordinator.DEFAULT_MAX_LOADS_TO_EXTRACT,
                OmsrLoadFlowCoordinator.DEFAULT_LOAD_API_CONCURRENCY);
    }

    public OmsrLoadJobStatus triggerLoad(boolean skipHwyHaulApis, int detailScrapeConcurrency) {
        return triggerLoad(skipHwyHaulApis, detailScrapeConcurrency, OmsrLoadFlowCoordinator.DEFAULT_MAX_LOADS_TO_EXTRACT);
    }

    public OmsrLoadJobStatus triggerLoad(
            boolean skipHwyHaulApis,
            int detailScrapeConcurrency,
            int maxLoadsToExtract
    ) {
        return triggerLoad(skipHwyHaulApis, detailScrapeConcurrency, maxLoadsToExtract,
                OmsrLoadFlowCoordinator.DEFAULT_LOAD_API_CONCURRENCY);
    }

    public OmsrLoadJobStatus triggerLoad(
            boolean skipHwyHaulApis,
            int detailScrapeConcurrency,
            int maxLoadsToExtract,
            int loadApiConcurrency
    ) {
        int normalizedConcurrency = Math.max(
                OmsrLoadFlowCoordinator.DEFAULT_DETAIL_SCRAPE_CONCURRENCY,
                detailScrapeConcurrency);
        int normalizedMaxLoadsToExtract = Math.max(
                OmsrLoadFlowCoordinator.DEFAULT_MAX_LOADS_TO_EXTRACT,
                maxLoadsToExtract);
        int normalizedLoadApiConcurrency = Math.max(
                OmsrLoadFlowCoordinator.DEFAULT_LOAD_API_CONCURRENCY,
                loadApiConcurrency);
        OmsrFlowAccepted accepted = agentService.startAgent(
                skipHwyHaulApis,
                normalizedConcurrency,
                normalizedMaxLoadsToExtract,
                normalizedLoadApiConcurrency);
        String jobId = UUID.randomUUID().toString();
        JobRecord record = new JobRecord(jobId, accepted.runId(), accepted.state(), Instant.now());
        jobs.put(jobId, record);
        latestJobId.set(jobId);
        log.info("Triggered OMSR load job " + jobId
                + " for run " + accepted.runId()
                + ". skipHwyHaulApis=" + skipHwyHaulApis
                + ", detailScrapeConcurrency=" + normalizedConcurrency
                + ", maxLoadsToExtract=" + normalizedMaxLoadsToExtract
                + ", loadApiConcurrency=" + normalizedLoadApiConcurrency);
        return record.snapshot();
    }

    public OmsrLoadJobStatus status(String jobId) {
        return refresh(requireJob(jobId));
    }

    public OmsrLoadJobStatus latestStatus() {
        String jobId = latestJobId.get();
        if (jobId == null) {
            throw new NoSuchElementException("No OMSR load job has been triggered.");
        }
        return status(jobId);
    }

    @Scheduled(
            fixedDelayString = "${agent.omsr.job.fixed-delay-ms:300000}",
            initialDelayString = "${agent.omsr.job.initial-delay-ms:30000}"
    )
    public void triggerScheduledLoad() {
        if (!scheduledEnabled) {
            return;
        }

        OmsrLoadJobStatus latest = latestStatusOrNull();
        if (latest != null && !isTerminal(latest.state())) {
            log.info("Skipping scheduled OMSR load job because job {} is still {}.", latest.jobId(), latest.state());
            return;
        }

        triggerLoad();
    }

    private OmsrLoadJobStatus latestStatusOrNull() {
        String jobId = latestJobId.get();
        if (jobId == null) {
            return null;
        }
        return status(jobId);
    }

    private OmsrLoadJobStatus refresh(JobRecord record) {
        AgentContext context = agentService.getRun(record.runId);
        return record.updateFrom(context);
    }

    private JobRecord requireJob(String jobId) {
        JobRecord record = jobs.get(jobId);
        if (record == null) {
            throw new NoSuchElementException("Unknown OMSR load job id: " + jobId);
        }
        return record;
    }

    private boolean isTerminal(AgentState state) {
        return state == AgentState.DONE || state == AgentState.FAILED;
    }

    private static class JobRecord {
        private final String jobId;
        private final String runId;
        private final Instant startedAt;
        private AgentState state;
        private Instant lastCheckedAt;
        private Instant completedAt;
        private String errorMessage;

        private JobRecord(String jobId, String runId, AgentState state, Instant startedAt) {
            this.jobId = jobId;
            this.runId = runId;
            this.state = state;
            this.startedAt = startedAt;
            this.lastCheckedAt = startedAt;
        }

        private synchronized OmsrLoadJobStatus updateFrom(AgentContext context) {
            Instant now = Instant.now();
            state = context.state;
            errorMessage = context.errorMessage;
            lastCheckedAt = now;
            if ((state == AgentState.DONE || state == AgentState.FAILED) && completedAt == null) {
                completedAt = now;
            }
            return snapshot();
        }

        private synchronized OmsrLoadJobStatus snapshot() {
            return new OmsrLoadJobStatus(
                    jobId,
                    runId,
                    state,
                    startedAt,
                    lastCheckedAt,
                    completedAt,
                    errorMessage
            );
        }
    }
}
