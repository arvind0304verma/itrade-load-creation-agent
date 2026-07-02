package com.hwyhaul.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.omsr")
public class OmsrBrowserConfig {

    private String authUrl = "https://omsr.itradenetwork.com/loginv2";
    private String ordersUrl = "https://omsr.itradenetwork.com/app/enterprise?activity=LogisticsC_OrderStatus";
    private String logoutUrl = "https://omsr.itradenetwork.com/logout";
    private String username = "";
    private String password = "";
    private String userNameSelector = "input[name='userName']";
    private String passwordSelector = "input[name='password']";
    private String submitSelector = "button[type='submit']";
    private String logoutSelector = "a[href*='/logout' i], button[aria-label*='logout' i], [aria-label*='logout' i], [data-testid*='logout' i]";
    private String logoutMenuSelector = "itn-user-headshot .mat-mdc-menu-trigger, itn-user-headshot [aria-haspopup='menu'], .itn-user-headshot";
    private String loadRowSelector = "tbody tr, [role='row']";
    private String loadLinkSelector = "a, button, [role='link']";
    private int maxLoadsFromFirstScreen = 0;
    private boolean headless = true;
    private boolean logoutEnabled = true;
    private String debugDirectory = "target/playwright-debug/omsr";

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

    public String getLogoutUrl() {
        return logoutUrl;
    }

    public void setLogoutUrl(String logoutUrl) {
        this.logoutUrl = logoutUrl;
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

    public String getUserNameSelector() {
        return userNameSelector;
    }

    public void setUserNameSelector(String userNameSelector) {
        this.userNameSelector = userNameSelector;
    }

    public String getPasswordSelector() {
        return passwordSelector;
    }

    public void setPasswordSelector(String passwordSelector) {
        this.passwordSelector = passwordSelector;
    }

    public String getSubmitSelector() {
        return submitSelector;
    }

    public void setSubmitSelector(String submitSelector) {
        this.submitSelector = submitSelector;
    }

    public String getLogoutSelector() {
        return logoutSelector;
    }

    public void setLogoutSelector(String logoutSelector) {
        this.logoutSelector = logoutSelector;
    }

    public String getLogoutMenuSelector() {
        return logoutMenuSelector;
    }

    public void setLogoutMenuSelector(String logoutMenuSelector) {
        this.logoutMenuSelector = logoutMenuSelector;
    }

    public String getLoadRowSelector() {
        return loadRowSelector;
    }

    public void setLoadRowSelector(String loadRowSelector) {
        this.loadRowSelector = loadRowSelector;
    }

    public String getLoadLinkSelector() {
        return loadLinkSelector;
    }

    public void setLoadLinkSelector(String loadLinkSelector) {
        this.loadLinkSelector = loadLinkSelector;
    }

    public int getMaxLoadsFromFirstScreen() {
        return maxLoadsFromFirstScreen;
    }

    public void setMaxLoadsFromFirstScreen(int maxLoadsFromFirstScreen) {
        this.maxLoadsFromFirstScreen = maxLoadsFromFirstScreen;
    }

    public boolean isHeadless() {
        return headless;
    }

    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    public boolean isLogoutEnabled() {
        return logoutEnabled;
    }

    public void setLogoutEnabled(boolean logoutEnabled) {
        this.logoutEnabled = logoutEnabled;
    }

    public String getDebugDirectory() {
        return debugDirectory;
    }

    public void setDebugDirectory(String debugDirectory) {
        this.debugDirectory = debugDirectory;
    }
}
