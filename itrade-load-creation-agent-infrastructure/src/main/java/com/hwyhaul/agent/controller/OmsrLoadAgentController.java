package com.hwyhaul.agent.controller;

import com.hwyhaul.agent.agent.AgentContext;
import com.hwyhaul.agent.agent.OmsrLoadAgentService;
import com.hwyhaul.agent.omsr.OmsrLoadJobService;
import com.hwyhaul.agent.omsr.OmsrLoadJobStatus;
import com.hwyhaul.agent.omsr.OmsrFlowAccepted;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/agent/omsr")
public class OmsrLoadAgentController {

    private final OmsrLoadAgentService service;
    private final OmsrLoadJobService jobService;

    public OmsrLoadAgentController(OmsrLoadAgentService service, OmsrLoadJobService jobService) {
        this.service = service;
        this.jobService = jobService;
    }

    @PostMapping("/create-load-payload")
    public AgentContext createLoadPayload(
            @RequestParam(name = "skipHwyHaulApis", defaultValue = "false") boolean skipHwyHaulApis,
            @RequestParam(name = "detailScrapeConcurrency", defaultValue = "1") int detailScrapeConcurrency,
            @RequestParam(name = "maxLoadsToExtract", defaultValue = "0") int maxLoadsToExtract,
            @RequestParam(name = "loadApiConcurrency", defaultValue = "1") int loadApiConcurrency
    ) throws Exception {
        return service.runAgent(skipHwyHaulApis, detailScrapeConcurrency, maxLoadsToExtract, loadApiConcurrency);
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OmsrFlowAccepted startRun(
            @RequestParam(name = "skipHwyHaulApis", defaultValue = "false") boolean skipHwyHaulApis,
            @RequestParam(name = "detailScrapeConcurrency", defaultValue = "1") int detailScrapeConcurrency,
            @RequestParam(name = "maxLoadsToExtract", defaultValue = "0") int maxLoadsToExtract,
            @RequestParam(name = "loadApiConcurrency", defaultValue = "1") int loadApiConcurrency
    ) {
        return service.startAgent(skipHwyHaulApis, detailScrapeConcurrency, maxLoadsToExtract, loadApiConcurrency);
    }

    @GetMapping("/runs/{runId}")
    public AgentContext getRun(@PathVariable String runId) {
        try {
            return service.getRun(runId);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    @PostMapping("/jobs/load")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OmsrLoadJobStatus triggerLoadJob(
            @RequestParam(name = "skipHwyHaulApis", defaultValue = "false") boolean skipHwyHaulApis,
            @RequestParam(name = "detailScrapeConcurrency", defaultValue = "1") int detailScrapeConcurrency,
            @RequestParam(name = "maxLoadsToExtract", defaultValue = "0") int maxLoadsToExtract,
            @RequestParam(name = "loadApiConcurrency", defaultValue = "1") int loadApiConcurrency
    ) {
        return jobService.triggerLoad(skipHwyHaulApis, detailScrapeConcurrency, maxLoadsToExtract, loadApiConcurrency);
    }

    @GetMapping("/jobs/latest")
    public OmsrLoadJobStatus getLatestJob() {
        try {
            return jobService.latestStatus();
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    @GetMapping("/jobs/{jobId}")
    public OmsrLoadJobStatus getJob(@PathVariable String jobId) {
        try {
            return jobService.status(jobId);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }
}
