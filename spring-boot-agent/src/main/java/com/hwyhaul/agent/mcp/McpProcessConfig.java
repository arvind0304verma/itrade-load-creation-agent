package com.hwyhaul.agent.mcp;

import com.hwyhaul.agent.config.AgentBrowserConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpProcessConfig {

    @Value("${mcp.jar-path:../java-mcp-server/target/java-mcp-server-0.0.1-SNAPSHOT.jar}")
    private String jarPath;

    private final AgentBrowserConfig browserConfig;

    public McpProcessConfig(AgentBrowserConfig browserConfig) {
        this.browserConfig = browserConfig;
    }

    public String[] buildCommand() {
        return new String[]{"java", "-jar", jarPath};
    }

    public ProcessBuilder buildProcessBuilder() {
        ProcessBuilder processBuilder = new ProcessBuilder(buildCommand());
        processBuilder.environment().put("MCP_AUTH_URL", browserConfig.getAuthUrl());
        processBuilder.environment().put("MCP_ORDERS_URL", browserConfig.getOrdersUrl());
        processBuilder.environment().put("MCP_USERNAME", browserConfig.getUsername());
        processBuilder.environment().put("MCP_PASSWORD", browserConfig.getPassword());
        processBuilder.environment().put("MCP_ORDER_ROW_SELECTOR", browserConfig.getOrderRowSelector());
        return processBuilder;
    }
}
