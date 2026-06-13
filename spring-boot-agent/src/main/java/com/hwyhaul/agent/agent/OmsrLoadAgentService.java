package com.hwyhaul.agent.agent;

import com.hwyhaul.agent.omsr.OmsrFlowAccepted;
import com.hwyhaul.agent.omsr.OmsrLoadFlowCoordinator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class OmsrLoadAgentService {

    private final OmsrLoadFlowCoordinator flowCoordinator;
    private final Duration runTimeout;

    public OmsrLoadAgentService(
            OmsrLoadFlowCoordinator flowCoordinator,
            @Value("${agent.omsr.run-timeout-ms:600000}") long runTimeoutMs
    ) {
        this.flowCoordinator = flowCoordinator;
        this.runTimeout = Duration.ofMillis(runTimeoutMs);
    }

    public AgentContext runAgent() throws Exception {
        return runAgent(
                false,
                OmsrLoadFlowCoordinator.DEFAULT_DETAIL_SCRAPE_CONCURRENCY,
                OmsrLoadFlowCoordinator.DEFAULT_MAX_LOADS_TO_EXTRACT,
                OmsrLoadFlowCoordinator.DEFAULT_LOAD_API_CONCURRENCY);
    }

    public AgentContext runAgent(boolean skipHwyHaulApis) throws Exception {
        return runAgent(
                skipHwyHaulApis,
                OmsrLoadFlowCoordinator.DEFAULT_DETAIL_SCRAPE_CONCURRENCY,
                OmsrLoadFlowCoordinator.DEFAULT_MAX_LOADS_TO_EXTRACT,
                OmsrLoadFlowCoordinator.DEFAULT_LOAD_API_CONCURRENCY);
    }

    public AgentContext runAgent(boolean skipHwyHaulApis, int detailScrapeConcurrency) throws Exception {
        return runAgent(skipHwyHaulApis, detailScrapeConcurrency, OmsrLoadFlowCoordinator.DEFAULT_MAX_LOADS_TO_EXTRACT);
    }

    public AgentContext runAgent(
            boolean skipHwyHaulApis,
            int detailScrapeConcurrency,
            int maxLoadsToExtract
    ) throws Exception {
        return runAgent(skipHwyHaulApis, detailScrapeConcurrency, maxLoadsToExtract,
                OmsrLoadFlowCoordinator.DEFAULT_LOAD_API_CONCURRENCY);
    }

    public AgentContext runAgent(
            boolean skipHwyHaulApis,
            int detailScrapeConcurrency,
            int maxLoadsToExtract,
            int loadApiConcurrency
    ) throws Exception {
        OmsrFlowAccepted accepted = startAgent(skipHwyHaulApis, detailScrapeConcurrency, maxLoadsToExtract, loadApiConcurrency);
        return flowCoordinator.awaitCompletion(accepted.runId(), runTimeout);
    }

    public OmsrFlowAccepted startAgent() {
        return startAgent(
                false,
                OmsrLoadFlowCoordinator.DEFAULT_DETAIL_SCRAPE_CONCURRENCY,
                OmsrLoadFlowCoordinator.DEFAULT_MAX_LOADS_TO_EXTRACT,
                OmsrLoadFlowCoordinator.DEFAULT_LOAD_API_CONCURRENCY);
    }

    public OmsrFlowAccepted startAgent(boolean skipHwyHaulApis) {
        return startAgent(
                skipHwyHaulApis,
                OmsrLoadFlowCoordinator.DEFAULT_DETAIL_SCRAPE_CONCURRENCY,
                OmsrLoadFlowCoordinator.DEFAULT_MAX_LOADS_TO_EXTRACT,
                OmsrLoadFlowCoordinator.DEFAULT_LOAD_API_CONCURRENCY);
    }

    public OmsrFlowAccepted startAgent(boolean skipHwyHaulApis, int detailScrapeConcurrency) {
        return startAgent(skipHwyHaulApis, detailScrapeConcurrency, OmsrLoadFlowCoordinator.DEFAULT_MAX_LOADS_TO_EXTRACT);
    }

    public OmsrFlowAccepted startAgent(
            boolean skipHwyHaulApis,
            int detailScrapeConcurrency,
            int maxLoadsToExtract
    ) {
        return startAgent(skipHwyHaulApis, detailScrapeConcurrency, maxLoadsToExtract,
                OmsrLoadFlowCoordinator.DEFAULT_LOAD_API_CONCURRENCY);
    }

    public OmsrFlowAccepted startAgent(
            boolean skipHwyHaulApis,
            int detailScrapeConcurrency,
            int maxLoadsToExtract,
            int loadApiConcurrency
    ) {
        return flowCoordinator.startFlow(skipHwyHaulApis, detailScrapeConcurrency, maxLoadsToExtract, loadApiConcurrency);
    }

    public AgentContext getRun(String runId) {
        return flowCoordinator.snapshot(runId);
    }
}
