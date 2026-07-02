package com.hwyhaul.agent.omsr;

import com.hwyhaul.agent.agent.AgentState;

import java.time.Instant;

public record OmsrLoadJobStatus(
        String jobId,
        String runId,
        AgentState state,
        Instant startedAt,
        Instant lastCheckedAt,
        Instant completedAt,
        String errorMessage
) {
}
