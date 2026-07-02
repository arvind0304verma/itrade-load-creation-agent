package com.hwyhaul.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hwyhaul.mcp.browser.BrowserClient;
import com.hwyhaul.mcp.browser.OrderRow;
import com.hwyhaul.mcp.model.CreateLoadPayload;

import java.util.List;

public class GetOrdersTool {

    private final ObjectMapper mapper = new ObjectMapper();

    public String execute() throws Exception {
        BrowserClient client = new BrowserClient();

        try {
            client.init();
            client.login();
            client.goToOrdersScreen();

            List<OrderRow> orders = client.extractOrders();
            CreateLoadPayload payload = CreateLoadPayloadBuilder.build(orders);
            payload.xHhToken = client.getXHhToken();

            return mapper.writeValueAsString(payload);
        } finally {
            client.close();
        }
    }
}
