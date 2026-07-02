package com.hwyhaul.agent.omsr;

import com.hwyhaul.agent.model.CapturedOrdersPayload;

import java.util.List;

public final class OmsrLoadEvents {

    private OmsrLoadEvents() {
    }

    public record FlowStarted(
            String runId,
            boolean skipHwyHaulApis,
            int detailScrapeConcurrency,
            int maxLoadsToExtract,
            int loadApiConcurrency
    ) {
    }

    public record LoadsCaptured(String runId, CapturedOrdersPayload capturedPayload) {
    }

    public record PayloadsMapped(String runId, List<OmsrMappedLoad> mappedLoads) {
    }

    public record LoadApiCallsCompleted(String runId, List<String> responses) {
    }

    public record HwyHaulApisSkipped(String runId, List<String> responses) {
    }

    public record FlowSkipped(String runId, String reasoning, String loadApiResponse) {
    }

    public record FlowFailed(String runId, Throwable error) {
    }
}
