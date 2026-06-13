package com.hwyhaul.agent.omsr.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hwyhaul.agent.agent.AgentState;
import com.hwyhaul.agent.loadapi.LoadApiClient;
import com.hwyhaul.agent.model.CreateLoadPayload;
import com.hwyhaul.agent.omsr.OmsrLoadEvents;
import com.hwyhaul.agent.omsr.OmsrLoadFlowCoordinator;
import com.hwyhaul.agent.omsr.OmsrMappedLoad;
import com.hwyhaul.agent.omsr.mongo.OmsrProcessedLoadStore;
import com.hwyhaul.agent.playwright.PlaywrightOrderScraper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OmsrLoadApiEventHandlerTest {

    @Test
    void skipHwyHaulApisPublishesSkipEventWithoutCallingHwyHaulClients() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();
        OmsrLoadFlowCoordinator coordinator = new OmsrLoadFlowCoordinator(publisher);
        String runId = coordinator.startFlow(true).runId();
        coordinator.update(runId, context -> context.state = AgentState.REASON);
        publisher.events.clear();

        PlaywrightOrderScraper hwyHaulAuthClient = mock(PlaywrightOrderScraper.class);
        LoadApiClient loadApiClient = mock(LoadApiClient.class);
        OmsrLoadApiEventHandler handler = new OmsrLoadApiEventHandler(
                coordinator,
                publisher,
                hwyHaulAuthClient,
                loadApiClient,
                new ObjectMapper()
        );

        handler.handle(new OmsrLoadEvents.PayloadsMapped(
                runId,
                List.of(new OmsrMappedLoad("ORDER-1", payload("ORDER-1")))
        ));

        verify(hwyHaulAuthClient, never()).loginAndGetToken();
        verify(loadApiClient, never()).createLoads(any(), any());

        OmsrLoadEvents.HwyHaulApisSkipped event = assertInstanceOf(
                OmsrLoadEvents.HwyHaulApisSkipped.class,
                publisher.events.get(0)
        );
        assertEquals(runId, event.runId());
        assertEquals(1, event.responses().size());
        assertEquals(AgentState.REASON, coordinator.snapshot(runId).state);
    }

    @Test
    void successfulLoadApiCallMarksLoadCompleted() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();
        OmsrLoadFlowCoordinator coordinator = new OmsrLoadFlowCoordinator(publisher);
        String runId = coordinator.startFlow(false).runId();
        coordinator.update(runId, context -> context.state = AgentState.REASON);
        publisher.events.clear();

        PlaywrightOrderScraper hwyHaulAuthClient = mock(PlaywrightOrderScraper.class);
        LoadApiClient loadApiClient = mock(LoadApiClient.class);
        OmsrProcessedLoadStore processedLoadStore = mock(OmsrProcessedLoadStore.class);
        when(hwyHaulAuthClient.loginAndGetToken()).thenReturn("token");
        when(loadApiClient.createLoads(any(), eq("token"))).thenReturn("created");
        OmsrLoadApiEventHandler handler = new OmsrLoadApiEventHandler(
                coordinator,
                publisher,
                hwyHaulAuthClient,
                loadApiClient,
                new ObjectMapper(),
                Optional.of(processedLoadStore)
        );

        handler.handle(new OmsrLoadEvents.PayloadsMapped(
                runId,
                List.of(new OmsrMappedLoad("ORDER-1", payload("ORDER-1"))))
        );

        verify(processedLoadStore).markCompleted(runId, "ORDER-1", "created");
        verify(processedLoadStore, never()).markFailed(anyString(), anyString(), any());
        OmsrLoadEvents.LoadApiCallsCompleted event = assertInstanceOf(
                OmsrLoadEvents.LoadApiCallsCompleted.class,
                publisher.events.get(0)
        );
        assertEquals(runId, event.runId());
        assertEquals(List.of("order ORDER-1: created"), event.responses());
    }

    @Test
    void loadApiConcurrencyGreaterThanOneCallsLoadApiInParallel() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();
        OmsrLoadFlowCoordinator coordinator = new OmsrLoadFlowCoordinator(publisher);
        String runId = coordinator.startFlow(false, 1, 0, 2).runId();
        coordinator.update(runId, context -> context.state = AgentState.REASON);
        publisher.events.clear();

        PlaywrightOrderScraper hwyHaulAuthClient = mock(PlaywrightOrderScraper.class);
        LoadApiClient loadApiClient = mock(LoadApiClient.class);
        OmsrProcessedLoadStore processedLoadStore = mock(OmsrProcessedLoadStore.class);
        CountDownLatch bothCallsStarted = new CountDownLatch(2);
        AtomicInteger activeCalls = new AtomicInteger();
        AtomicInteger maxActiveCalls = new AtomicInteger();
        when(hwyHaulAuthClient.loginAndGetToken()).thenReturn("token");
        when(loadApiClient.createLoads(any(), eq("token"))).thenAnswer(invocation -> {
            int active = activeCalls.incrementAndGet();
            maxActiveCalls.accumulateAndGet(active, Math::max);
            bothCallsStarted.countDown();
            try {
                assertTrue(bothCallsStarted.await(2, TimeUnit.SECONDS));
                CreateLoadPayload payload = invocation.getArgument(0);
                return "created " + payload.orders.get(0).customerLoadNumber;
            } finally {
                activeCalls.decrementAndGet();
            }
        });
        OmsrLoadApiEventHandler handler = new OmsrLoadApiEventHandler(
                coordinator,
                publisher,
                hwyHaulAuthClient,
                loadApiClient,
                new ObjectMapper(),
                Optional.of(processedLoadStore)
        );

        handler.handle(new OmsrLoadEvents.PayloadsMapped(
                runId,
                List.of(
                        new OmsrMappedLoad("ORDER-1", payload("ORDER-1")),
                        new OmsrMappedLoad("ORDER-2", payload("ORDER-2"))))
        );

        assertTrue(maxActiveCalls.get() > 1);
        verify(processedLoadStore).markCompleted(runId, "ORDER-1", "created ORDER-1");
        verify(processedLoadStore).markCompleted(runId, "ORDER-2", "created ORDER-2");
        OmsrLoadEvents.LoadApiCallsCompleted event = assertInstanceOf(
                OmsrLoadEvents.LoadApiCallsCompleted.class,
                publisher.events.get(0)
        );
        assertEquals(List.of("order ORDER-1: created ORDER-1", "order ORDER-2: created ORDER-2"), event.responses());
    }

    @Test
    void failedLoadApiCallMarksLoadFailedAndPublishesFlowFailed() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();
        OmsrLoadFlowCoordinator coordinator = new OmsrLoadFlowCoordinator(publisher);
        String runId = coordinator.startFlow(false).runId();
        coordinator.update(runId, context -> context.state = AgentState.REASON);
        publisher.events.clear();

        PlaywrightOrderScraper hwyHaulAuthClient = mock(PlaywrightOrderScraper.class);
        LoadApiClient loadApiClient = mock(LoadApiClient.class);
        OmsrProcessedLoadStore processedLoadStore = mock(OmsrProcessedLoadStore.class);
        IllegalStateException error = new IllegalStateException("api down");
        when(hwyHaulAuthClient.loginAndGetToken()).thenReturn("token");
        when(loadApiClient.createLoads(any(), eq("token"))).thenThrow(error);
        OmsrLoadApiEventHandler handler = new OmsrLoadApiEventHandler(
                coordinator,
                publisher,
                hwyHaulAuthClient,
                loadApiClient,
                new ObjectMapper(),
                Optional.of(processedLoadStore)
        );

        handler.handle(new OmsrLoadEvents.PayloadsMapped(
                runId,
                List.of(new OmsrMappedLoad("ORDER-1", payload("ORDER-1"))))
        );

        verify(processedLoadStore).markFailed(runId, "ORDER-1", error);
        verify(processedLoadStore, never()).markCompleted(anyString(), anyString(), anyString());
        OmsrLoadEvents.FlowFailed event = assertInstanceOf(
                OmsrLoadEvents.FlowFailed.class,
                publisher.events.get(0)
        );
        assertEquals(runId, event.runId());
        assertEquals(error, event.error());
    }

    private CreateLoadPayload payload(String orderId) {
        CreateLoadPayload payload = new CreateLoadPayload();
        CreateLoadPayload.Order order = new CreateLoadPayload.Order();
        order.customerLoadNumber = orderId;
        payload.orders = List.of(order);
        return payload;
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
