package com.hwyhaul.agent.omsr.mongo;

import com.hwyhaul.agent.config.AgentBrowserConfig;
import com.hwyhaul.agent.config.AgentLoadConfig;
import com.hwyhaul.agent.config.OmsrBrowserConfig;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OmsrMongoConfigApplierTest {

    @Test
    void appliesNestedMongoOverridesToRuntimeConfig() {
        OmsrBrowserConfig omsrConfig = new OmsrBrowserConfig();
        AgentBrowserConfig browserConfig = new AgentBrowserConfig();
        AgentLoadConfig loadConfig = new AgentLoadConfig();
        OmsrMongoConfigApplier applier = new OmsrMongoConfigApplier(omsrConfig, browserConfig, loadConfig);
        Document document = new Document("omsr", new Document("username", "omsr-user")
                .append("password", "omsr-password")
                .append("headless", false)
                .append("logoutEnabled", false)
                .append("logoutUrl", "https://omsr.itradenetwork.com/logout-test")
                .append("logoutSelector", "[data-testid='logout-test']")
                .append("logoutMenuSelector", "[data-testid='user-menu-test']")
                .append("maxLoadsFromFirstScreen", 3))
                .append("hwyhaul", new Document("username", "hwyhaul-user")
                        .append("password", "hwyhaul-password"))
                .append("load", new Document("shipperId", "shipper-config-id")
                        .append("commodityId", "commodity-config-id")
                        .append("unitPrice", "1250.50")
                        .append("creditRecipient", new Document("userId", "credit-user-id")
                                .append("commissionPlanId", "commission-plan-id"))
                        .append("address", new Document("id", new Document("pickup", "pickup-address-id")
                                .append("dropoff", "dropoff-address-id"))));

        applier.apply(document);

        assertEquals("omsr-user", omsrConfig.getUsername());
        assertEquals("omsr-password", omsrConfig.getPassword());
        assertFalse(omsrConfig.isHeadless());
        assertFalse(omsrConfig.isLogoutEnabled());
        assertEquals("https://omsr.itradenetwork.com/logout-test", omsrConfig.getLogoutUrl());
        assertEquals("[data-testid='logout-test']", omsrConfig.getLogoutSelector());
        assertEquals("[data-testid='user-menu-test']", omsrConfig.getLogoutMenuSelector());
        assertEquals(3, omsrConfig.getMaxLoadsFromFirstScreen());
        assertEquals("hwyhaul-user", browserConfig.getUsername());
        assertEquals("hwyhaul-password", browserConfig.getPassword());
        assertEquals("shipper-config-id", loadConfig.getShipperId());
        assertEquals("commodity-config-id", loadConfig.getCommodityId());
        assertEquals(new BigDecimal("1250.50"), loadConfig.getUnitPrice());
        assertEquals("credit-user-id", loadConfig.getCreditRecipient().getUserId());
        assertEquals("commission-plan-id", loadConfig.getCreditRecipient().getCommissionPlanId());
        assertEquals("pickup-address-id", loadConfig.getAddress().getId().getPickup());
        assertEquals("dropoff-address-id", loadConfig.getAddress().getId().getDropoff());
    }
}
