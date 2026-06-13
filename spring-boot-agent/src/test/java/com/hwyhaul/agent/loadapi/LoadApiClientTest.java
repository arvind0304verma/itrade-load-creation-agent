package com.hwyhaul.agent.loadapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hwyhaul.agent.config.AgentLoadConfig;
import com.hwyhaul.agent.mapper.LoadPayloadMapper;
import com.hwyhaul.agent.model.CapturedOrdersPayload;
import com.hwyhaul.agent.model.CreateLoadPayload;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadApiClientTest {

    @Test
    void createLoadsSkipsWhenShipperIdIsMissing() throws Exception {
        AgentLoadConfig config = new AgentLoadConfig();
        config.getCompany().setId("configured-company-id");
        config.setShipperId("shipper-config-id");
        config.setCommodityId("commodity-config-id");
        config.setPaymentAddonTypeId("payment-addon-type-id");
        config.getCreditRecipient().setUserId("credit-user-id");
        config.getCreditRecipient().setCommissionPlanId("commission-plan-id");
        config.getAddress().getId().setPickup("pickup-config-id");
        config.getAddress().getId().setDropoff("dropoff-config-id");

        LoadPayloadMapper mapper = new LoadPayloadMapper(config);

        CapturedOrdersPayload.CapturedOrder order = new CapturedOrdersPayload.CapturedOrder();
        order.externalOrderId = "ORDER-1";

        CreateLoadPayload payload = mapper.mapSingle(order);
        payload.orders.get(0).shipperId = null;

        LoadApiClient client = new LoadApiClient(
                RestClient.builder(),
                new ObjectMapper(),
                "https://example.invalid/loads",
                "api-key",
                true,
                1_000,
                1_000
        );

        String response = client.createLoads(payload, "token");

        assertTrue(response.startsWith("LOAD API POST skipped."));
        assertTrue(response.contains("orders[0].shipperId"));
    }
}
