package com.hwyhaul.agent.agent;

import org.springframework.stereotype.Service;

@Service
public class LoadAgentService {

    private final LoadAgentGraph graph;

    public LoadAgentService(LoadAgentGraph graph) {
        this.graph = graph;
    }

    public AgentContext runAgent() throws Exception {
        return graph.run();
    }
}