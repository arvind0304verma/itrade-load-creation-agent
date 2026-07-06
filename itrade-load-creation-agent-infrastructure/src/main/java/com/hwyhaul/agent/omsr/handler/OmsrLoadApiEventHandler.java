package com.hwyhaul.agent.omsr.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hwyhaul.agent.agent.AgentContext;
import com.hwyhaul.agent.agent.AgentState;
import com.hwyhaul.agent.loadapi.LoadApiClient;
import com.hwyhaul.agent.omsr.OmsrLoadEvents;
import com.hwyhaul.agent.omsr.OmsrLoadFlowCoordinator;
import com.hwyhaul.agent.omsr.OmsrMappedLoad;
import com.hwyhaul.agent.omsr.mongo.OmsrProcessedLoadStore;
import com.hwyhaul.agent.intake.mongo.PipelineRunStore;
import com.hwyhaul.agent.playwright.PlaywrightOrderScraper;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class OmsrLoadApiEventHandler {

    private static final Logger log = LoggerFactory.getLogger(OmsrLoadApiEventHandler.class);

    private final OmsrLoadFlowCoordinator flowCoordinator;
    private final ApplicationEventPublisher eventPublisher;
    private final PlaywrightOrderScraper hwyHaulAuthClient;
    private final LoadApiClient loadApiClient;
    private final ObjectMapper mapper;
    private final Optional<OmsrProcessedLoadStore> processedLoadStore;
    private final Optional<PipelineRunStore> pipelineRunStore;

    @Autowired
    public OmsrLoadApiEventHandler(
            OmsrLoadFlowCoordinator flowCoordinator,
            ApplicationEventPublisher eventPublisher,
            PlaywrightOrderScraper hwyHaulAuthClient,
            LoadApiClient loadApiClient,
            ObjectMapper mapper,
            Optional<OmsrProcessedLoadStore> processedLoadStore,
            Optional<PipelineRunStore> pipelineRunStore
    ) {
        this.flowCoordinator = flowCoordinator;
        this.eventPublisher = eventPublisher;
        this.hwyHaulAuthClient = hwyHaulAuthClient;
        this.loadApiClient = loadApiClient;
        this.mapper = mapper;
        this.processedLoadStore = processedLoadStore == null ? Optional.empty() : processedLoadStore;
        this.pipelineRunStore = pipelineRunStore == null ? Optional.empty() : pipelineRunStore;
    }

    OmsrLoadApiEventHandler(
            OmsrLoadFlowCoordinator flowCoordinator,
            ApplicationEventPublisher eventPublisher,
            PlaywrightOrderScraper hwyHaulAuthClient,
            LoadApiClient loadApiClient,
            ObjectMapper mapper,
            Optional<OmsrProcessedLoadStore> processedLoadStore
    ) {
        this(flowCoordinator, eventPublisher, hwyHaulAuthClient, loadApiClient, mapper, processedLoadStore, Optional.empty());
    }

    OmsrLoadApiEventHandler(
            OmsrLoadFlowCoordinator flowCoordinator,
            ApplicationEventPublisher eventPublisher,
            PlaywrightOrderScraper hwyHaulAuthClient,
            LoadApiClient loadApiClient,
            ObjectMapper mapper
    ) {
        this(flowCoordinator, eventPublisher, hwyHaulAuthClient, loadApiClient, mapper, Optional.empty(), Optional.empty());
    }

    @Async("omsrTaskExecutor")
    @EventListener
    public void handle(OmsrLoadEvents.PayloadsMapped event) {
        try {
            AgentContext snapshot = flowCoordinator.snapshot(event.runId());
            if (snapshot.skipHwyHaulApis) {
                eventPublisher.publishEvent(new OmsrLoadEvents.HwyHaulApisSkipped(
                        event.runId(),
                        logMappedPayloadsWithoutHwyHaulApis(event.runId(), event.mappedLoads())
                ));
                return;
            }

            flowCoordinator.update(event.runId(), context -> context.state = AgentState.CALL_LOAD_API);
            String hwyHaulToken = snapshot.hwyHaulToken;
            if (hwyHaulToken == null || hwyHaulToken.isBlank()) {
                // No token captured during mapping (login deferred or failed) - authenticate now.
                hwyHaulToken = hwyHaulAuthClient.loginAndGetToken();
                String capturedToken = hwyHaulToken;
                flowCoordinator.update(event.runId(), context -> context.hwyHaulToken = capturedToken);
            }

            List<String> responses = callLoadApis(
                    event.runId(),
                    event.mappedLoads(),
                    hwyHaulToken,
                    snapshot.loadApiConcurrency);

            eventPublisher.publishEvent(new OmsrLoadEvents.LoadApiCallsCompleted(event.runId(), List.copyOf(responses)));
        } catch (Exception e) {
            eventPublisher.publishEvent(new OmsrLoadEvents.FlowFailed(event.runId(), e));
        }
    }

    private List<String> callLoadApis(
            String runId,
            List<OmsrMappedLoad> mappedLoads,
            String hwyHaulToken,
            int loadApiConcurrency
    ) throws Exception {
        if (mappedLoads == null || mappedLoads.isEmpty()) {
            return List.of();
        }

        int workerCount = Math.min(Math.max(1, loadApiConcurrency), mappedLoads.size());
        if (workerCount == 1) {
            return callLoadApisSequentially(runId, mappedLoads, hwyHaulToken);
        }
        return callLoadApisInParallel(runId, mappedLoads, hwyHaulToken, workerCount);
    }

    private List<String> callLoadApisSequentially(
            String runId,
            List<OmsrMappedLoad> mappedLoads,
            String hwyHaulToken
    ) throws Exception {
        List<String> responses = new ArrayList<>(mappedLoads.size());
        for (OmsrMappedLoad mappedLoad : mappedLoads) {
            responses.add(callSingleLoadApi(runId, mappedLoad, hwyHaulToken));
        }
        return responses;
    }

    private List<String> callLoadApisInParallel(
            String runId,
            List<OmsrMappedLoad> mappedLoads,
            String hwyHaulToken,
            int workerCount
    ) throws Exception {
        log.info("Calling HwyHaul Load API for {} mapped load(s) with loadApiConcurrency={}.",
                mappedLoads.size(), workerCount);

        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        try {
            List<Future<String>> futures = new ArrayList<>(mappedLoads.size());
            for (OmsrMappedLoad mappedLoad : mappedLoads) {
                futures.add(executor.submit(() -> callSingleLoadApi(runId, mappedLoad, hwyHaulToken)));
            }

            List<String> responses = new ArrayList<>(mappedLoads.size());
            Exception firstFailure = null;
            for (Future<String> future : futures) {
                try {
                    responses.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelOutstanding(futures);
                    throw new IllegalStateException("Interrupted while calling HwyHaul Load API in parallel.", e);
                } catch (ExecutionException e) {
                    if (firstFailure == null) {
                        firstFailure = asException(e.getCause());
                    }
                }
            }

            if (firstFailure != null) {
                throw firstFailure;
            }
            return responses;
        } finally {
            executor.shutdownNow();
        }
    }

    private String callSingleLoadApi(String runId, OmsrMappedLoad mappedLoad, String hwyHaulToken) throws Exception {
        String customerLoadNumber = mappedLoad.externalOrderId();
        markPipelineProcessing(customerLoadNumber);
        try {
            String response = loadApiClient.createLoads(mappedLoad.payload(), hwyHaulToken);
            markCompleted(runId, mappedLoad.externalOrderId(), response);
            markPipelineCompleted(customerLoadNumber, response);
            log.debug("OMSR load payload for order {}: {}",
                    mappedLoad.externalOrderId(),
                    mapper.writeValueAsString(mappedLoad.payload()));
            log.debug("OMSR Load API response for order {}: {}", mappedLoad.externalOrderId(), response);
            return "order " + mappedLoad.externalOrderId() + ": " + response;
        } catch (Exception e) {
            markFailed(runId, mappedLoad.externalOrderId(), e);
            markPipelineFailed(customerLoadNumber, e);
            throw e;
        }
    }

    private void cancelOutstanding(List<Future<String>> futures) {
        for (Future<String> future : futures) {
            future.cancel(true);
        }
    }

    private Exception asException(Throwable error) {
        if (error instanceof Exception exception) {
            return exception;
        }
        return new IllegalStateException("HwyHaul Load API worker failed.", error);
    }

    private List<String> logMappedPayloadsWithoutHwyHaulApis(String runId, List<OmsrMappedLoad> mappedLoads) throws Exception {
        List<String> responses = new ArrayList<>(mappedLoads.size());
        for (OmsrMappedLoad mappedLoad : mappedLoads) {
            log.info("Skipping HwyHaul auth and Load API calls for OMSR order {}. Mapped LOAD payload: {}",
                    mappedLoad.externalOrderId(),
                    mapper.writeValueAsString(mappedLoad.payload()));
            markSkipped(runId, mappedLoad.externalOrderId(), "HwyHaul auth and Load API calls skipped by request.");
            responses.add("order " + mappedLoad.externalOrderId()
                    + ": HwyHaul auth and Load API calls skipped by request; mapped LOAD payload logged.");
        }
        return responses;
    }

    private void markCompleted(String runId, String loadNumber, String response) {
        processedLoadStore.ifPresent(store -> store.markCompleted(runId, loadNumber, response));
    }

    private void markFailed(String runId, String loadNumber, Throwable error) {
        processedLoadStore.ifPresent(store -> store.markFailed(runId, loadNumber, error));
    }

    private void markSkipped(String runId, String loadNumber, String reason) {
        processedLoadStore.ifPresent(store -> store.markSkipped(runId, loadNumber, reason));
    }

    private void markPipelineProcessing(String customerLoadNumber) {
        pipelineRunStore.ifPresent(store -> store.markProcessing(customerLoadNumber));
    }

    private void markPipelineCompleted(String customerLoadNumber, String loadApiResponse) {
        pipelineRunStore.ifPresent(store -> store.markCompleted(customerLoadNumber, loadApiResponse));
    }

    private void markPipelineFailed(String customerLoadNumber, Throwable error) {
        pipelineRunStore.ifPresent(store -> store.markFailed(customerLoadNumber, error));
    }
}
