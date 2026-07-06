package com.hwyhaul.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hwyhaul.agent.loadapi.LoadApiClient;
import com.hwyhaul.agent.mapper.LoadPayloadMapper;
import com.hwyhaul.agent.mcp.McpClientService;
import com.hwyhaul.agent.model.CapturedOrdersPayload;
import com.hwyhaul.agent.model.CreateLoadPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class LoadAgentGraph {

    private static final Logger log = LoggerFactory.getLogger(LoadAgentGraph.class);

    private final McpClientService mcp;
    private final LoadApiClient loadApiClient;
    private final LoadPayloadMapper loadPayloadMapper;
    private final ObjectMapper mapper = new ObjectMapper();

    public LoadAgentGraph(
            McpClientService mcp,
            LoadApiClient loadApiClient,
            LoadPayloadMapper loadPayloadMapper
    ) {
        this.mcp = mcp;
        this.loadApiClient = loadApiClient;
        this.loadPayloadMapper = loadPayloadMapper;
    }

    public AgentContext run() throws Exception {
        AgentContext ctx = new AgentContext();
        ctx.state = AgentState.INIT;

        // CALL MCP SERVER
        ctx.state = AgentState.CALL_MCP;
        CapturedOrdersPayload capturedPayload = mcp.callGetOrdersTool();
        ctx.capturedPayload = capturedPayload;
        ctx.hwyHaulToken = capturedPayload == null ? null : capturedPayload.xHhToken;

        ctx.state = AgentState.REASON;
        List<CapturedOrdersPayload.CapturedOrder> loads = capturedPayload == null || capturedPayload.loads == null
                ? List.of()
                : capturedPayload.loads;
        if (loads.isEmpty()) {
            ctx.payload = null;
            ctx.payloads = List.of();
            ctx.reasoning = "Captured no order rows from MCP; LLM reasoning skipped.";
            ctx.loadApiResponse = "LOAD API POST skipped. No order rows matched the current orders filter.";
            ctx.state = AgentState.DONE;
            return ctx;
        }

        List<CreateLoadPayload> payloads = new ArrayList<>(loads.size());
        List<String> responses = new ArrayList<>(loads.size());
        for (CapturedOrdersPayload.CapturedOrder capturedOrder : loads) {
            if (capturedOrder == null || capturedOrder.externalOrderId == null || capturedOrder.externalOrderId.isBlank()) {
                continue;
            }
            CreateLoadPayload payload = loadPayloadMapper.mapSingle(capturedOrder, ctx.hwyHaulToken);
            payloads.add(payload);

            ctx.state = AgentState.CALL_LOAD_API;
            String response = loadApiClient.createLoads(payload, ctx.hwyHaulToken);
            responses.add("order " + capturedOrder.externalOrderId + ": " + response);
            log.debug("Load payload for order {}: {}", capturedOrder.externalOrderId, mapper.writeValueAsString(payload));
            log.debug("Load API response for order {}: {}", capturedOrder.externalOrderId, response);
        }

        if (payloads.isEmpty()) {
            ctx.payload = null;
            ctx.payloads = List.of();
            ctx.reasoning = "Captured rows were present but none could be mapped into valid loads; LLM reasoning skipped.";
            ctx.loadApiResponse = "LOAD API POST skipped. No order rows matched the current orders filter.";
            ctx.state = AgentState.DONE;
            return ctx;
        }

        ctx.payloads = payloads;
        ctx.payload = payloads.get(0);
        ctx.reasoning = "Mapped each captured order row into a single LOAD API request; LLM reasoning skipped.";
        ctx.loadApiResponse = String.join(System.lineSeparator(), responses);

        ctx.state = AgentState.DONE;
        return ctx;
    }
}
