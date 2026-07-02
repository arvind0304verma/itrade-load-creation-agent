package com.hwyhaul.agent.omsr.handler;

import com.hwyhaul.agent.agent.AgentContext;
import com.hwyhaul.agent.agent.AgentState;
import com.hwyhaul.agent.config.AgentLoadConfig;
import com.hwyhaul.agent.mapper.LoadPayloadMapper;
import com.hwyhaul.agent.model.CapturedOrdersPayload;
import com.hwyhaul.agent.omsr.OmsrLoadEvents;
import com.hwyhaul.agent.omsr.OmsrLoadFlowCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class OmsrMappingEventHandlerTest {

    @Test
    void mapsCapturedLoadsAndPublishesPayloadEvent() {
        CapturingPublisher publisher = new CapturingPublisher();
        OmsrLoadFlowCoordinator coordinator = new OmsrLoadFlowCoordinator(publisher);
        String runId = coordinator.startFlow().runId();
        publisher.events.clear();

        OmsrMappingEventHandler handler = new OmsrMappingEventHandler(
                coordinator,
                publisher,
                new LoadPayloadMapper(loadConfig())
        );

        CapturedOrdersPayload capturedPayload = new CapturedOrdersPayload();
        CapturedOrdersPayload.CapturedOrder capturedOrder = new CapturedOrdersPayload.CapturedOrder();
        capturedOrder.externalOrderId = "34851996";
        capturedPayload.loads = List.of(capturedOrder);

        handler.handle(new OmsrLoadEvents.LoadsCaptured(runId, capturedPayload));

        OmsrLoadEvents.PayloadsMapped event = assertInstanceOf(
                OmsrLoadEvents.PayloadsMapped.class,
                publisher.events.get(0)
        );
        assertEquals(runId, event.runId());
        assertEquals(1, event.mappedLoads().size());
        assertEquals("34851996", event.mappedLoads().get(0).externalOrderId());
        assertEquals("34851996", event.mappedLoads().get(0).payload().orders.get(0).customerLoadNumber);

        AgentContext snapshot = coordinator.snapshot(runId);
        assertEquals(AgentState.REASON, snapshot.state);
        assertEquals(1, snapshot.payloads.size());
        assertEquals("34851996", snapshot.payload.orders.get(0).customerLoadNumber);
    }

    @Test
    void emptyCapturedLoadsPublishesSkippedEvent() {
        CapturingPublisher publisher = new CapturingPublisher();
        OmsrLoadFlowCoordinator coordinator = new OmsrLoadFlowCoordinator(publisher);
        String runId = coordinator.startFlow().runId();
        publisher.events.clear();

        OmsrMappingEventHandler handler = new OmsrMappingEventHandler(
                coordinator,
                publisher,
                new LoadPayloadMapper(loadConfig())
        );

        CapturedOrdersPayload capturedPayload = new CapturedOrdersPayload();
        capturedPayload.loads = List.of();

        handler.handle(new OmsrLoadEvents.LoadsCaptured(runId, capturedPayload));

        OmsrLoadEvents.FlowSkipped event = assertInstanceOf(
                OmsrLoadEvents.FlowSkipped.class,
                publisher.events.get(0)
        );
        assertEquals(runId, event.runId());
        assertEquals("LOAD API POST skipped. No order rows matched the current orders filter.", event.loadApiResponse());
    }

    private AgentLoadConfig loadConfig() {
        AgentLoadConfig config = new AgentLoadConfig();
        config.getCompany().setId("configured-company-id");
        config.setShipperId("shipper-config-id");
        config.setCommodityId("commodity-config-id");
        config.setPaymentAddonTypeId("payment-addon-type-id");
        config.getCreditRecipient().setUserId("credit-user-id");
        config.getCreditRecipient().setCommissionPlanId("commission-plan-id");
        config.getAddress().getId().setPickup("pickup-config-id");
        config.getAddress().getId().setDropoff("dropoff-config-id");
        return config;
    }

    private static class CapturingPublisher implements ApplicationEventPublisher {
        private final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(ApplicationEvent event) {
            events.add(event);
        }

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }
    }
}
