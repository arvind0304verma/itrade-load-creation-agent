package com.hwyhaul.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.browser")
public class AgentBrowserConfig {

    private String authUrl = "https://qa.ops.hwyhaul.com/auth";
    private String ordersUrl = "https://qa.ops.hwyhaul.com/orders?activeTab=OPEN_ORDERS&status=ORDER_CREATED&sort=shipDate,asc&type=SALES";
    private String username = "superadmin.user1@hwyhaul.com";
    private String password = "password";
    private String orderRowSelector = "[data-testid='orders-table-row'], .ant-table-tbody > tr.ant-table-row:not([aria-hidden='true']), table tbody tr[data-row-key]";

    public String getAuthUrl() {
        return authUrl;
    }

    public void setAuthUrl(String authUrl) {
        this.authUrl = authUrl;
    }

    public String getOrdersUrl() {
        return ordersUrl;
    }

    public void setOrdersUrl(String ordersUrl) {
        this.ordersUrl = ordersUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getOrderRowSelector() {
        return orderRowSelector;
    }

    public void setOrderRowSelector(String orderRowSelector) {
        this.orderRowSelector = orderRowSelector;
    }
}
