package com.hwyhaul.agent.agent;

public enum AgentState {
    INIT,
    SCRAPE_DOM,
    CALL_MCP,
    REASON,
    CALL_LOAD_API,
    DONE,
    FAILED
}
