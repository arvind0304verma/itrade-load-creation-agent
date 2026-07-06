package com.hwyhaul.agent.omsr.handler;

import com.hwyhaul.agent.agent.AgentContext;
import com.hwyhaul.agent.agent.AgentState;
import com.hwyhaul.agent.mapper.LoadPayloadMapper;
import com.hwyhaul.agent.model.CapturedOrdersPayload;
import com.hwyhaul.agent.model.CreateLoadPayload;
import com.hwyhaul.agent.omsr.OmsrLoadEvents;
import com.hwyhaul.agent.omsr.OmsrLoadFlowCoordinator;
import com.hwyhaul.agent.omsr.OmsrMappedLoad;
import com.hwyhaul.agent.playwright.PlaywrightOrderScraper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class OmsrMappingEventHandler {

    private static final Logger log = LoggerFactory.getLogger(OmsrMappingEventHandler.class);

    private final OmsrLoadFlowCoordinator flowCoordinator;
    private final ApplicationEventPublisher eventPublisher;
    private final LoadPayloadMapper loadPayloadMapper;
    private final PlaywrightOrderScraper hwyHaulAuthClient;

    public OmsrMappingEventHandler(
            OmsrLoadFlowCoordinator flowCoordinator,
            ApplicationEventPublisher eventPublisher,
            LoadPayloadMapper loadPayloadMapper,
            PlaywrightOrderScraper hwyHaulAuthClient
    ) {
        this.flowCoordinator = flowCoordinator;
        this.eventPublisher = eventPublisher;
        this.loadPayloadMapper = loadPayloadMapper;
        this.hwyHaulAuthClient = hwyHaulAuthClient;
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

            // Address-id resolution calls a HwyHaul API that needs the x-hh-token from an authenticated
            // session. Log in up front (unless HwyHaul APIs are skipped) so the lookup runs with a real
            // token, and reuse that token in the Load API stage. If login fails here we fall back to the
            // configured default address ids and let the Load API stage surface the auth failure.
            String hwyHaulToken = resolveHwyHaulToken(event.runId());

            List<OmsrMappedLoad> mappedLoads = new ArrayList<>(capturedLoads.size());
            for (CapturedOrdersPayload.CapturedOrder capturedLoad : capturedLoads) {
                if (capturedLoad == null
                        || capturedLoad.externalOrderId == null
                        || capturedLoad.externalOrderId.isBlank()) {
                    continue;
                }
                CreateLoadPayload payload = loadPayloadMapper.mapSingle(capturedLoad, hwyHaulToken);
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

    /**
     * Logs into HwyHaul before mapping so the address-id lookup runs with a live x-hh-token, and stores
     * the token on the flow context for reuse by the Load API stage. Returns {@code null} when HwyHaul
     * APIs are skipped for the run, or when login fails (best effort): the mapper then falls back to the
     * configured default address ids and the Load API stage performs the authoritative login.
     */
    private String resolveHwyHaulToken(String runId) {
        AgentContext snapshot = flowCoordinator.snapshot(runId);
        if (snapshot.skipHwyHaulApis) {
            return null;
        }
        try {
            String token = hwyHaulAuthClient.loginAndGetToken();
            flowCoordinator.update(runId, context -> context.hwyHaulToken = token);
            return token;
        } catch (RuntimeException e) {
            log.warn("HwyHaul login before mapping failed; address-id lookup will fall back to configured "
                    + "default address ids and the Load API stage will retry login. Cause: {}", e.getMessage());
            return null;
        }
    }

    private List<CapturedOrdersPayload.CapturedOrder> capturedLoads(CapturedOrdersPayload capturedPayload) {
        if (capturedPayload == null || capturedPayload.loads == null) {
            return List.of();
        }
        return capturedPayload.loads;
    }
}
