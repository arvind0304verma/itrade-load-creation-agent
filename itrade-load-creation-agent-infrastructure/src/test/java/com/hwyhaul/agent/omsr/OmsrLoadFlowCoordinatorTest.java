package com.hwyhaul.agent.omsr;

import com.hwyhaul.agent.agent.AgentContext;
import com.hwyhaul.agent.agent.AgentState;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OmsrLoadFlowCoordinatorTest {

    @Test
    void startFlowPublishesStartEventAndExposesInitialSnapshot() {
        CapturingPublisher publisher = new CapturingPublisher();
        OmsrLoadFlowCoordinator coordinator = new OmsrLoadFlowCoordinator(publisher);

        OmsrFlowAccepted accepted = coordinator.startFlow();

        assertEquals(AgentState.INIT, accepted.state());
        OmsrLoadEvents.FlowStarted started = assertInstanceOf(
                OmsrLoadEvents.FlowStarted.class,
                publisher.events.get(0)
        );
        assertEquals(accepted.runId(), started.runId());
        assertFalse(started.skipHwyHaulApis());
        assertEquals(1, started.detailScrapeConcurrency());
        assertEquals(0, started.maxLoadsToExtract());
        assertEquals(1, started.loadApiConcurrency());

        AgentContext snapshot = coordinator.snapshot(accepted.runId());
        assertEquals(accepted.runId(), snapshot.runId);
        assertEquals(AgentState.INIT, snapshot.state);
        assertFalse(snapshot.skipHwyHaulApis);
        assertEquals(1, snapshot.detailScrapeConcurrency);
        assertEquals(0, snapshot.maxLoadsToExtract);
        assertEquals(1, snapshot.loadApiConcurrency);
    }

    @Test
    void startFlowCanMarkHwyHaulApisSkipped() {
        CapturingPublisher publisher = new CapturingPublisher();
        OmsrLoadFlowCoordinator coordinator = new OmsrLoadFlowCoordinator(publisher);

        OmsrFlowAccepted accepted = coordinator.startFlow(true);

        OmsrLoadEvents.FlowStarted started = assertInstanceOf(
                OmsrLoadEvents.FlowStarted.class,
                publisher.events.get(0)
        );
        assertEquals(accepted.runId(), started.runId());
        assertTrue(started.skipHwyHaulApis());
        assertEquals(1, started.detailScrapeConcurrency());
        assertEquals(0, started.maxLoadsToExtract());
        assertEquals(1, started.loadApiConcurrency());

        AgentContext snapshot = coordinator.snapshot(accepted.runId());
        assertTrue(snapshot.skipHwyHaulApis);
        assertEquals(1, snapshot.detailScrapeConcurrency);
        assertEquals(0, snapshot.maxLoadsToExtract);
        assertEquals(1, snapshot.loadApiConcurrency);
    }

    @Test
    void startFlowAcceptsDetailScrapeConcurrency() {
        CapturingPublisher publisher = new CapturingPublisher();
        OmsrLoadFlowCoordinator coordinator = new OmsrLoadFlowCoordinator(publisher);

        OmsrFlowAccepted accepted = coordinator.startFlow(false, 4);

        OmsrLoadEvents.FlowStarted started = assertInstanceOf(
                OmsrLoadEvents.FlowStarted.class,
                publisher.events.get(0)
        );
        assertEquals(accepted.runId(), started.runId());
        assertEquals(4, started.detailScrapeConcurrency());
        assertEquals(0, started.maxLoadsToExtract());
        assertEquals(1, started.loadApiConcurrency());

        AgentContext snapshot = coordinator.snapshot(accepted.runId());
        assertEquals(4, snapshot.detailScrapeConcurrency);
        assertEquals(0, snapshot.maxLoadsToExtract);
        assertEquals(1, snapshot.loadApiConcurrency);
    }

    @Test
    void startFlowAcceptsMaxLoadsToExtract() {
        CapturingPublisher publisher = new CapturingPublisher();
        OmsrLoadFlowCoordinator coordinator = new OmsrLoadFlowCoordinator(publisher);

        OmsrFlowAccepted accepted = coordinator.startFlow(false, 4, 10);

        OmsrLoadEvents.FlowStarted started = assertInstanceOf(
                OmsrLoadEvents.FlowStarted.class,
                publisher.events.get(0)
        );
        assertEquals(accepted.runId(), started.runId());
        assertEquals(4, started.detailScrapeConcurrency());
        assertEquals(10, started.maxLoadsToExtract());
        assertEquals(1, started.loadApiConcurrency());

        AgentContext snapshot = coordinator.snapshot(accepted.runId());
        assertEquals(4, snapshot.detailScrapeConcurrency);
        assertEquals(10, snapshot.maxLoadsToExtract);
        assertEquals(1, snapshot.loadApiConcurrency);
    }

    @Test
    void startFlowAcceptsLoadApiConcurrency() {
        CapturingPublisher publisher = new CapturingPublisher();
        OmsrLoadFlowCoordinator coordinator = new OmsrLoadFlowCoordinator(publisher);

        OmsrFlowAccepted accepted = coordinator.startFlow(false, 4, 10, 3);

        OmsrLoadEvents.FlowStarted started = assertInstanceOf(
                OmsrLoadEvents.FlowStarted.class,
                publisher.events.get(0)
        );
        assertEquals(accepted.runId(), started.runId());
        assertEquals(4, started.detailScrapeConcurrency());
        assertEquals(10, started.maxLoadsToExtract());
        assertEquals(3, started.loadApiConcurrency());

        AgentContext snapshot = coordinator.snapshot(accepted.runId());
        assertEquals(4, snapshot.detailScrapeConcurrency);
        assertEquals(10, snapshot.maxLoadsToExtract);
        assertEquals(3, snapshot.loadApiConcurrency);
    }

    @Test
    void completeResolvesAwaitingRun() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();
        OmsrLoadFlowCoordinator coordinator = new OmsrLoadFlowCoordinator(publisher);
        String runId = coordinator.startFlow().runId();

        coordinator.complete(runId, context -> {
            context.state = AgentState.DONE;
            context.reasoning = "completed";
        });

        AgentContext completed = coordinator.awaitCompletion(runId, Duration.ofMillis(100));

        assertEquals(runId, completed.runId);
        assertEquals(AgentState.DONE, completed.state);
        assertEquals("completed", completed.reasoning);
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
