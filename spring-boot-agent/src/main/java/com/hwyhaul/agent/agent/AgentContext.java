package com.hwyhaul.agent.agent;

import com.hwyhaul.agent.model.CreateLoadPayload;
import com.hwyhaul.agent.model.CapturedOrdersPayload;

import java.util.List;

public class AgentContext {
    public String runId;
    public AgentState state;
    public boolean skipHwyHaulApis;
    public int detailScrapeConcurrency;
    public int maxLoadsToExtract;
    public int loadApiConcurrency;
    public CapturedOrdersPayload capturedPayload;
    public CreateLoadPayload payload;
    public List<CreateLoadPayload> payloads;
    public String reasoning;
    public String omsrToken;
    public String hwyHaulToken;
    public String loadApiResponse;
    public String errorMessage;
}
