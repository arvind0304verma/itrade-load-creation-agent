package com.hwyhaul.agent.controller;

import com.hwyhaul.agent.agent.AgentContext;
import com.hwyhaul.agent.agent.LoadAgentService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/agent")
public class LoadAgentController {

    private final LoadAgentService service;

    public LoadAgentController(LoadAgentService service) {
        this.service = service;
    }

    @PostMapping("/create-loads-from-orders")
    public AgentContext createLoadsFromOrders() throws Exception {
        return service.runAgent();
    }
}