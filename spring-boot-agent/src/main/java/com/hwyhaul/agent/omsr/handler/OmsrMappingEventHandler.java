package com.hwyhaul.agent.omsr.handler;

import com.hwyhaul.agent.agent.AgentState;
import com.hwyhaul.agent.mapper.LoadPayloadMapper;
import com.hwyhaul.agent.model.CapturedOrdersPayload;
import com.hwyhaul.agent.model.CreateLoadPayload;
import com.hwyhaul.agent.omsr.OmsrLoadEvents;
import com.hwyhaul.agent.omsr.OmsrLoadFlowCoordinator;
import com.hwyhaul.agent.omsr.OmsrMappedLoad;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class OmsrMappingEventHandler {

    private final OmsrLoadFlowCoordinator flowCoordinator;
    private final ApplicationEventPublisher eventPublisher;
    private final LoadPayloadMapper loadPayloadMapper;

    public OmsrMappingEventHandler(
            OmsrLoadFlowCoordinator flowCoordinator,
            ApplicationEventPublisher eventPublisher,
            LoadPayloadMapper loadPayloadMapper
    ) {
        this.flowCoordinator = flowCoordinator;
        this.eventPublisher = eventPublisher;
        this.loadPayloadMapper = loadPayloadMapper;
    }

    @Async("omsrTaskExecutor")
    @EventListener
    public void handle(OmsrLoadEvents.LoadsCaptured event) {
        try {
            flowCoordinator.update(event.runId(), context -> context.state = AgentState.REASON);
            List<CapturedOrdersPayload.CapturedOrder> capturedLoads = capturedLoads(event.capturedPayload());
            if (capturedLoads.isEmpty()) {
                eventPublisher.publishEvent(new OmsrLoadEvents.FlowSkipped(
                        event.runId(),
                        "Captured no OMSR loads from the available order-list pages; LLM reasoning skipped.",
                        "LOAD API POST skipped. No order rows matched the current orders filter."
                ));
                return;
            }

            List<OmsrMappedLoad> mappedLoads = new ArrayList<>(capturedLoads.size());
            for (CapturedOrdersPayload.CapturedOrder capturedLoad : capturedLoads) {
                if (capturedLoad == null
                        || capturedLoad.externalOrderId == null
                        || capturedLoad.externalOrderId.isBlank()) {
                    continue;
                }
                CreateLoadPayload payload = loadPayloadMapper.mapSingle(capturedLoad);
                mappedLoads.add(new OmsrMappedLoad(capturedLoad.externalOrderId, payload));
            }

            if (mappedLoads.isEmpty()) {
                eventPublisher.publishEvent(new OmsrLoadEvents.FlowSkipped(
                        event.runId(),
                        "Captured OMSR rows were present but none could be mapped into valid loads; LLM reasoning skipped.",
                        "LOAD API POST skipped. No order rows matched the current orders filter."
                ));
                return;
            }

            List<CreateLoadPayload> payloads = mappedLoads.stream()
                    .map(OmsrMappedLoad::payload)
                    .toList();
            flowCoordinator.update(event.runId(), context -> {
                context.payloads = payloads;
                context.payload = payloads.get(0);
            });
            eventPublisher.publishEvent(new OmsrLoadEvents.PayloadsMapped(event.runId(), List.copyOf(mappedLoads)));
        } catch (Exception e) {
            eventPublisher.publishEvent(new OmsrLoadEvents.FlowFailed(event.runId(), e));
        }
    }

    private List<CapturedOrdersPayload.CapturedOrder> capturedLoads(CapturedOrdersPayload capturedPayload) {
        if (capturedPayload == null || capturedPayload.loads == null) {
            return List.of();
        }
        return capturedPayload.loads;
    }
}
