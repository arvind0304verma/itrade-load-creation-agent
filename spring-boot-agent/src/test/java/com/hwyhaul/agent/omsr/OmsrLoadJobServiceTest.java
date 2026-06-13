package com.hwyhaul.agent.omsr;

import com.hwyhaul.agent.agent.AgentContext;
import com.hwyhaul.agent.agent.AgentState;
import com.hwyhaul.agent.agent.OmsrLoadAgentService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OmsrLoadJobServiceTest {

    @Test
    void triggerLoadStartsEventDrivenRunAndReturnsJobStatus() {
        OmsrLoadAgentService agentService = mock(OmsrLoadAgentService.class);
        when(agentService.startAgent(false, 1, 0, 1)).thenReturn(new OmsrFlowAccepted("run-1", AgentState.INIT));
        OmsrLoadJobService jobService = new OmsrLoadJobService(agentService, false);

        OmsrLoadJobStatus status = jobService.triggerLoad();

        assertNotNull(status.jobId());
        assertEquals("run-1", status.runId());
        assertEquals(AgentState.INIT, status.state());
        assertNotNull(status.startedAt());
        assertNotNull(status.lastCheckedAt());
    }

    @Test
    void triggerLoadPassesSkipHwyHaulApisToBackingRun() {
        OmsrLoadAgentService agentService = mock(OmsrLoadAgentService.class);
        when(agentService.startAgent(true, 1, 0, 1)).thenReturn(new OmsrFlowAccepted("run-1", AgentState.INIT));
        OmsrLoadJobService jobService = new OmsrLoadJobService(agentService, false);

        OmsrLoadJobStatus status = jobService.triggerLoad(true);

        assertNotNull(status.jobId());
        assertEquals("run-1", status.runId());
        assertEquals(AgentState.INIT, status.state());
    }

    @Test
    void triggerLoadPassesDetailScrapeConcurrencyToBackingRun() {
        OmsrLoadAgentService agentService = mock(OmsrLoadAgentService.class);
        when(agentService.startAgent(true, 4, 0, 1)).thenReturn(new OmsrFlowAccepted("run-1", AgentState.INIT));
        OmsrLoadJobService jobService = new OmsrLoadJobService(agentService, false);

        OmsrLoadJobStatus status = jobService.triggerLoad(true, 4);

        assertNotNull(status.jobId());
        assertEquals("run-1", status.runId());
        assertEquals(AgentState.INIT, status.state());
    }

    @Test
    void triggerLoadPassesMaxLoadsToExtractToBackingRun() {
        OmsrLoadAgentService agentService = mock(OmsrLoadAgentService.class);
        when(agentService.startAgent(true, 4, 10, 1)).thenReturn(new OmsrFlowAccepted("run-1", AgentState.INIT));
        OmsrLoadJobService jobService = new OmsrLoadJobService(agentService, false);

        OmsrLoadJobStatus status = jobService.triggerLoad(true, 4, 10);

        assertNotNull(status.jobId());
        assertEquals("run-1", status.runId());
        assertEquals(AgentState.INIT, status.state());
    }

    @Test
    void triggerLoadPassesLoadApiConcurrencyToBackingRun() {
        OmsrLoadAgentService agentService = mock(OmsrLoadAgentService.class);
        when(agentService.startAgent(true, 4, 10, 3)).thenReturn(new OmsrFlowAccepted("run-1", AgentState.INIT));
        OmsrLoadJobService jobService = new OmsrLoadJobService(agentService, false);

        OmsrLoadJobStatus status = jobService.triggerLoad(true, 4, 10, 3);

        assertNotNull(status.jobId());
        assertEquals("run-1", status.runId());
        assertEquals(AgentState.INIT, status.state());
    }

    @Test
    void statusRefreshesFromBackingRun() {
        OmsrLoadAgentService agentService = mock(OmsrLoadAgentService.class);
        when(agentService.startAgent(false, 1, 0, 1)).thenReturn(new OmsrFlowAccepted("run-1", AgentState.INIT));

        AgentContext completed = new AgentContext();
        completed.runId = "run-1";
        completed.state = AgentState.DONE;
        when(agentService.getRun("run-1")).thenReturn(completed);

        OmsrLoadJobService jobService = new OmsrLoadJobService(agentService, false);
        OmsrLoadJobStatus started = jobService.triggerLoad();

        OmsrLoadJobStatus refreshed = jobService.status(started.jobId());

        assertEquals(started.jobId(), refreshed.jobId());
        assertEquals("run-1", refreshed.runId());
        assertEquals(AgentState.DONE, refreshed.state());
        assertNotNull(refreshed.completedAt());
    }
}
