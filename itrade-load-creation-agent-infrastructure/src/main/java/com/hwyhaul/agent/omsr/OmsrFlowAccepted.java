package com.hwyhaul.agent.omsr;

import com.hwyhaul.agent.agent.AgentState;

public record OmsrFlowAccepted(String runId, AgentState state) {
}
