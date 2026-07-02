package com.hwyhaul.agent.omsr.mongo;

import com.hwyhaul.agent.config.AgentBrowserConfig;
import com.hwyhaul.agent.config.AgentLoadConfig;
import com.hwyhaul.agent.config.OmsrBrowserConfig;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class OmsrMongoConfigApplier {

    private final OmsrBrowserConfig omsrBrowserConfig;
    private final AgentBrowserConfig agentBrowserConfig;
    private final AgentLoadConfig agentLoadConfig;

    public OmsrMongoConfigApplier(
            OmsrBrowserConfig omsrBrowserConfig,
            AgentBrowserConfig agentBrowserConfig,
            AgentLoadConfig agentLoadConfig
    ) {
        this.omsrBrowserConfig = omsrBrowserConfig;
        this.agentBrowserConfig = agentBrowserConfig;
        this.agentLoadConfig = agentLoadConfig;
    }

    public synchronized void apply(Document document) {
        applyOmsrBrowserConfig(document);
        applyHwyHaulBrowserConfig(document);
        applyLoadConfig(document);
    }

    private void applyOmsrBrowserConfig(Document document) {
        setString(omsrBrowserConfig::setAuthUrl, document,
                "agent.omsr.auth-url", "agent.omsr.authUrl", "omsr.authUrl", "omsr.auth_url");
        setString(omsrBrowserConfig::setOrdersUrl, document,
                "agent.omsr.orders-url", "agent.omsr.ordersUrl", "omsr.ordersUrl", "omsr.orders_url");
        setString(omsrBrowserConfig::setLogoutUrl, document,
                "agent.omsr.logout-url", "agent.omsr.logoutUrl", "omsr.logoutUrl", "omsr.logout_url");
        setString(omsrBrowserConfig::setUsername, document,
                "agent.omsr.username", "omsr.username", "omsr.credentials.username", "thirdParty.username");
        setString(omsrBrowserConfig::setPassword, document,
                "agent.omsr.password", "omsr.password", "omsr.credentials.password", "thirdParty.password");
        setString(omsrBrowserConfig::setUserNameSelector, document,
                "agent.omsr.user-name-selector", "agent.omsr.userNameSelector", "omsr.userNameSelector");
        setString(omsrBrowserConfig::setPasswordSelector, document,
                "agent.omsr.password-selector", "agent.omsr.passwordSelector", "omsr.passwordSelector");
        setString(omsrBrowserConfig::setSubmitSelector, document,
                "agent.omsr.submit-selector", "agent.omsr.submitSelector", "omsr.submitSelector");
        setString(omsrBrowserConfig::setLogoutSelector, document,
                "agent.omsr.logout-selector", "agent.omsr.logoutSelector", "omsr.logoutSelector");
        setString(omsrBrowserConfig::setLogoutMenuSelector, document,
                "agent.omsr.logout-menu-selector", "agent.omsr.logoutMenuSelector", "omsr.logoutMenuSelector");
        setString(omsrBrowserConfig::setLoadRowSelector, document,
                "agent.omsr.load-row-selector", "agent.omsr.loadRowSelector", "omsr.loadRowSelector");
        setString(omsrBrowserConfig::setLoadLinkSelector, document,
                "agent.omsr.load-link-selector", "agent.omsr.loadLinkSelector", "omsr.loadLinkSelector");
        setInteger(omsrBrowserConfig::setMaxLoadsFromFirstScreen, document,
                "agent.omsr.max-loads-from-first-screen", "agent.omsr.maxLoadsFromFirstScreen",
                "omsr.maxLoadsFromFirstScreen");
        setBoolean(omsrBrowserConfig::setHeadless, document,
                "agent.omsr.headless", "omsr.headless");
        setBoolean(omsrBrowserConfig::setLogoutEnabled, document,
                "agent.omsr.logout-enabled", "agent.omsr.logoutEnabled", "omsr.logoutEnabled");
        setString(omsrBrowserConfig::setDebugDirectory, document,
                "agent.omsr.debug-directory", "agent.omsr.debugDirectory", "omsr.debugDirectory");
    }

    private void applyHwyHaulBrowserConfig(Document document) {
        setString(agentBrowserConfig::setAuthUrl, document,
                "agent.browser.auth-url", "agent.browser.authUrl", "hwyhaul.authUrl");
        setString(agentBrowserConfig::setOrdersUrl, document,
                "agent.browser.orders-url", "agent.browser.ordersUrl", "hwyhaul.ordersUrl");
        setString(agentBrowserConfig::setUsername, document,
                "agent.browser.username", "hwyhaul.username", "hwyhaul.credentials.username");
        setString(agentBrowserConfig::setPassword, document,
                "agent.browser.password", "hwyhaul.password", "hwyhaul.credentials.password");
        setString(agentBrowserConfig::setOrderRowSelector, document,
                "agent.browser.order-row-selector", "agent.browser.orderRowSelector", "hwyhaul.orderRowSelector");
    }

    private void applyLoadConfig(Document document) {
        setString(agentLoadConfig::setSize, document, "agent.load.size", "load.size");
        setString(agentLoadConfig::setDriverCombination, document,
                "agent.load.driver-combination", "agent.load.driverCombination", "load.driverCombination");
        setString(agentLoadConfig::setPackaging, document, "agent.load.packaging", "load.packaging");
        setDecimal(agentLoadConfig::setCargoValueMax, document,
                "agent.load.cargo-value-max", "agent.load.cargoValueMax", "load.cargoValueMax");
        setDecimal(agentLoadConfig::setCargoValue, document,
                "agent.load.cargo-value", "agent.load.cargoValue", "load.cargoValue");
        setString(agentLoadConfig::setEquipment, document, "agent.load.equipment", "load.equipment");
        setString(agentLoadConfig::setType, document, "agent.load.type", "load.type");
        setString(agentLoadConfig::setPricingType, document,
                "agent.load.pricing-type", "agent.load.pricingType", "load.pricingType");
        setString(agentLoadConfig::setPaymentAddonTypeId, document,
                "agent.load.payment-addon-type-id", "agent.load.paymentAddonTypeId", "load.paymentAddonTypeId");
        setDecimal(agentLoadConfig::setUnitPrice, document,
                "agent.load.unit-price", "agent.load.unitPrice", "load.unitPrice");
        setInteger(agentLoadConfig::setUnitCount, document,
                "agent.load.unit-count", "agent.load.unitCount", "load.unitCount");
        setString(agentLoadConfig::setShipperId, document,
                "agent.load.shipper-id", "agent.load.shipperId", "load.shipperId");
        setString(agentLoadConfig::setCommodityId, document,
                "agent.load.commodity-id", "agent.load.commodityId", "load.commodityId");
        setString(agentLoadConfig.getCompany()::setId, document,
                "agent.load.company.id", "load.company.id", "load.companyId");
        setString(agentLoadConfig.getCurrency()::setName, document,
                "agent.load.currency.name", "load.currency.name", "load.currencyName");
        setString(agentLoadConfig.getCurrency()::setSymbol, document,
                "agent.load.currency.symbol", "load.currency.symbol", "load.currencySymbol");
        setString(agentLoadConfig.getCreditRecipient()::setUserId, document,
                "agent.load.credit-recipient.user-id", "agent.load.creditRecipient.userId",
                "load.creditRecipient.userId");
        setString(agentLoadConfig.getCreditRecipient()::setCommissionPlanId, document,
                "agent.load.credit-recipient.commission-plan-id",
                "agent.load.creditRecipient.commissionPlanId",
                "load.creditRecipient.commissionPlanId");
        setString(agentLoadConfig.getAddress().getId()::setPickup, document,
                "agent.load.address.id.pickup", "load.address.id.pickup", "load.pickupAddressId");
        setString(agentLoadConfig.getAddress().getId()::setDropoff, document,
                "agent.load.address.id.dropoff", "load.address.id.dropoff", "load.dropoffAddressId");
    }

    private void setString(StringSetter setter, Document document, String... paths) {
        String value = OmsrMongoDocumentReader.string(document, paths);
        if (value != null) {
            setter.set(value);
        }
    }

    private void setBoolean(BooleanSetter setter, Document document, String... paths) {
        Boolean value = OmsrMongoDocumentReader.bool(document, paths);
        if (value != null) {
            setter.set(value);
        }
    }

    private void setInteger(IntegerSetter setter, Document document, String... paths) {
        Integer value = OmsrMongoDocumentReader.integer(document, paths);
        if (value != null) {
            setter.set(value);
        }
    }

    private void setDecimal(DecimalSetter setter, Document document, String... paths) {
        BigDecimal value = OmsrMongoDocumentReader.decimal(document, paths);
        if (value != null) {
            setter.set(value);
        }
    }

    private interface StringSetter {
        void set(String value);
    }

    private interface BooleanSetter {
        void set(boolean value);
    }

    private interface IntegerSetter {
        void set(int value);
    }

    private interface DecimalSetter {
        void set(BigDecimal value);
    }
}
