package com.hwyhaul.agent.omsr.handler;

import com.hwyhaul.agent.agent.AgentState;
import com.hwyhaul.agent.model.CapturedOrdersPayload;
import com.hwyhaul.agent.omsr.OmsrLoadEvents;
import com.hwyhaul.agent.omsr.OmsrLoadFlowCoordinator;
import com.hwyhaul.agent.omsr.mongo.OmsrProcessedLoadStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class OmsrTerminalEventHandler {

    private static final Logger log = LoggerFactory.getLogger(OmsrTerminalEventHandler.class);

    private final OmsrLoadFlowCoordinator flowCoordinator;
    private final Optional<OmsrProcessedLoadStore> processedLoadStore;

    @Autowired
    public OmsrTerminalEventHandler(
            OmsrLoadFlowCoordinator flowCoordinator,
            Optional<OmsrProcessedLoadStore> processedLoadStore
    ) {
        this.flowCoordinator = flowCoordinator;
        this.processedLoadStore = processedLoadStore == null ? Optional.empty() : processedLoadStore;
    }

    OmsrTerminalEventHandler(OmsrLoadFlowCoordinator flowCoordinator) {
        this(flowCoordinator, Optional.empty());
    }

    @EventListener
    public void handle(OmsrLoadEvents.LoadApiCallsCompleted event) {
        flowCoordinator.complete(event.runId(), context -> {
            context.reasoning = "Captured OMSR loads across all available order-list pages and created one LOAD API request per load.";
            context.loadApiResponse = String.join(System.lineSeparator(), event.responses());
            context.state = AgentState.DONE;
        });
    }

    @EventListener
    public void handle(OmsrLoadEvents.HwyHaulApisSkipped event) {
        flowCoordinator.complete(event.runId(), context -> {
            context.reasoning = "Captured OMSR loads across all available order-list pages and skipped HwyHaul auth and Load API calls by request.";
            context.loadApiResponse = String.join(System.lineSeparator(), event.responses());
            context.state = AgentState.DONE;
        });
    }

    @EventListener
    public void handle(OmsrLoadEvents.FlowSkipped event) {
        markCapturedLoadsSkipped(event.runId(), event.reasoning());
        flowCoordinator.complete(event.runId(), context -> {
            context.payload = null;
            context.payloads = List.of();
            context.reasoning = event.reasoning();
            context.loadApiResponse = event.loadApiResponse();
            context.state = AgentState.DONE;
        });
    }

    @EventListener
    public void handle(OmsrLoadEvents.FlowFailed event) {
        log.error("OMSR load flow {} failed.", event.runId(), event.error());
        markCapturedLoadsFailed(event.runId(), event.error());
        flowCoordinator.fail(event.runId(), event.error());
    }

    private void markCapturedLoadsSkipped(String runId, String reason) {
        processedLoadStore.ifPresent(store -> {
            for (CapturedOrdersPayload.CapturedOrder load : capturedLoads(runId)) {
                store.markSkipped(runId, load.externalOrderId, reason);
            }
        });
    }

    private void markCapturedLoadsFailed(String runId, Throwable error) {
        processedLoadStore.ifPresent(store -> {
            for (CapturedOrdersPayload.CapturedOrder load : capturedLoads(runId)) {
                store.markFailed(runId, load.externalOrderId, error);
            }
        });
    }

    private List<CapturedOrdersPayload.CapturedOrder> capturedLoads(String runId) {
        CapturedOrdersPayload capturedPayload = flowCoordinator.snapshot(runId).capturedPayload;
        if (capturedPayload == null || capturedPayload.loads == null) {
            return List.of();
        }
        return capturedPayload.loads;
    }
}
