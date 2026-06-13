package com.hwyhaul.agent.loadapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hwyhaul.agent.model.CreateLoadPayload;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class LoadApiClient {

    private static final Logger log = LoggerFactory.getLogger(LoadApiClient.class);

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String createLoadUrl;
    private final String apiKey;
    private final boolean enabled;

    public LoadApiClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper mapper,
            @Value("${load.api.create-url:}") String createLoadUrl,
            @Value("${load.api.x-api-key:4206d6c3-16bf-444b-9e5f-36775f91c29c}") String apiKey,
            @Value("${load.api.enabled:false}") boolean enabled,
            @Value("${load.api.connect-timeout-ms:10000}") long connectTimeoutMs,
            @Value("${load.api.read-timeout-ms:60000}") long readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = restClientBuilder
                .requestFactory(requestFactory)
                .build();
        this.mapper = mapper;
        this.createLoadUrl = createLoadUrl;
        this.apiKey = apiKey;
        this.enabled = enabled;
    }

    public String createLoads(CreateLoadPayload payload, String hwyHaulToken) throws Exception {
        if (payload == null || payload.orders == null || payload.orders.isEmpty()) {
            return "LOAD API POST skipped. No order rows matched the current orders filter.";
        }

        if (!enabled) {
            return "LOAD API POST skipped. Set load.api.enabled=true to enable. Payload: "
                    + mapper.writeValueAsString(payload);
        }

        if (createLoadUrl == null || createLoadUrl.isBlank()) {
            return "LOAD API POST skipped. Configure load.api.create-url. Payload: "
                    + mapper.writeValueAsString(payload);
        }

        if (hwyHaulToken == null || hwyHaulToken.isBlank()) {
            throw new IllegalStateException("LOAD API POST failed before request. Missing HwyHaul x-hh-token after login capture.");
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("LOAD API POST failed before request. Missing load.api.x-api-key.");
        }

        List<String> missingIds = missingIdFields(payload);
        if (!missingIds.isEmpty()) {
            String message = "LOAD API POST skipped. Missing required id fields: " + missingIds
                    + ". Verify the resolved company, shipper, address, commodity, recipient, schedule, and supplement defaults.";
            log.warn(message);
            return message;
        }

        boolean hwyHaulTokenPresent = hwyHaulToken != null && !hwyHaulToken.isBlank();
        boolean apiKeyPresent = apiKey != null && !apiKey.isBlank();

        log.debug("Calling Load API. url={}, hwyHaulTokenPresent={}, xApiKeyPresent={}, payload={}",
                createLoadUrl, hwyHaulTokenPresent, apiKeyPresent, mapper.writeValueAsString(payload));

        long startedAt = System.nanoTime();
        try {
            String response = restClient.post()
                    .uri(createLoadUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("x-hh-token", hwyHaulToken)
                    .header("x-api-key", apiKey)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            log.debug("Load API call completed in {} ms", elapsedMillis(startedAt));
            return response;
        } catch (RestClientResponseException e) {
            StringBuilder message = new StringBuilder("Load API call failed with ")
                    .append(e.getStatusCode().value())
                    .append(' ')
                    .append(e.getStatusText())
                    .append(" after ")
                    .append(elapsedMillis(startedAt))
                    .append(" ms. URL: ")
                    .append(createLoadUrl);

            String responseBody = e.getResponseBodyAsString();
            if (responseBody != null && !responseBody.isBlank()) {
                message.append(". Response body: ").append(responseBody);
            }
            List<String> omittedFields = omittedPayloadFields(payload);
            if (!omittedFields.isEmpty()) {
                message.append(". Null or omitted payload fields: ").append(omittedFields);
            }
            message.append(". Payload sent: ").append(mapper.writeValueAsString(payload));
            message.append(". Verify the resolved company, shipper, address, commodity, recipient, schedule, and supplement defaults.");
            throw new IllegalStateException(message.toString(), e);
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("Load API call failed or timed out after "
                    + elapsedMillis(startedAt) + " ms. URL: " + createLoadUrl, e);
        }
    }

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private List<String> missingIdFields(CreateLoadPayload payload) {
        List<String> missing = new ArrayList<>();
        if (payload == null || payload.orders == null) {
            return missing;
        }

        if (!hasLoadManagementTeamId(payload.supplementData)) {
            missing.add("supplementData.loadManagementTeam.id");
        }

        for (int orderIndex = 0; orderIndex < payload.orders.size(); orderIndex++) {
            CreateLoadPayload.Order order = payload.orders.get(orderIndex);
            if (order == null) {
                continue;
            }

            if (order.company == null || isBlank(order.company.id)) {
                missing.add("orders[" + orderIndex + "].company.id");
            }
            if (isBlank(order.shipperId)) {
                missing.add("orders[" + orderIndex + "].shipperId");
            }
            if (order.creditRecipient == null || isBlank(order.creditRecipient.userId)) {
                missing.add("orders[" + orderIndex + "].creditRecipient.userId");
            }
            if (order.creditRecipient == null || isBlank(order.creditRecipient.commissionPlanId)) {
                missing.add("orders[" + orderIndex + "].creditRecipient.commissionPlanId");
            }
            if (order.supplementData == null || order.supplementData.isEmpty()) {
                missing.add("orders[" + orderIndex + "].supplementData");
            }
            if (!hasLoadManagementTeamId(order.supplementData)) {
                missing.add("orders[" + orderIndex + "].supplementData.loadManagementTeam.id");
            }

            if (order.charges != null) {
                for (int chargeIndex = 0; chargeIndex < order.charges.size(); chargeIndex++) {
                    CreateLoadPayload.Charge charge = order.charges.get(chargeIndex);
                    if (charge == null || charge.paymentAddonType == null || isBlank(charge.paymentAddonType.id)) {
                        missing.add("orders[" + orderIndex + "].charges[" + chargeIndex + "].paymentAddonType.id");
                    }
                    if (charge == null || charge.paymentAddonType == null || isBlank(charge.paymentAddonType.externalId)) {
                        missing.add("orders[" + orderIndex + "].charges[" + chargeIndex + "].paymentAddonType.externalId");
                    }
                }
            }

            if (order.waypoints != null) {
                for (int waypointIndex = 0; waypointIndex < order.waypoints.size(); waypointIndex++) {
                    CreateLoadPayload.Waypoint waypoint = order.waypoints.get(waypointIndex);
                    if (waypoint == null) {
                        continue;
                    }

                    if (waypoint.addressDTO == null || isBlank(waypoint.addressDTO.id)) {
                        missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].addressDTO.id");
                    }
                    if (waypoint.addressDTO == null || isBlank(waypoint.addressDTO.city)) {
                        missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].addressDTO.city");
                    }
                    if (waypoint.addressDTO == null || isBlank(waypoint.addressDTO.state)) {
                        missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].addressDTO.state");
                    }
                    if (waypoint.addressDTO == null || isBlank(waypoint.addressDTO.zip)) {
                        missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].addressDTO.zip");
                    }
                    if (waypoint.addressDTO == null || isBlank(waypoint.addressDTO.timezone)) {
                        missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].addressDTO.timezone");
                    }
                    if (waypoint.addressDTO == null || waypoint.addressDTO.lat == null) {
                        missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].addressDTO.lat");
                    }
                    if (waypoint.addressDTO == null || waypoint.addressDTO.lon == null) {
                        missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].addressDTO.lon");
                    }
                    if (waypoint.lat == null) {
                        missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].lat");
                    }
                    if (waypoint.lon == null) {
                        missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].lon");
                    }
                    if (isBlank(waypoint.timezone)) {
                        missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].timezone");
                    }
                    if (waypoint.weight == null) {
                        missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].weight");
                    }
                    if (waypoint.palletCount == null) {
                        missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].palletCount");
                    }
                    if (waypoint.caseCount == null) {
                        missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].caseCount");
                    }
                    if ("PICK".equalsIgnoreCase(waypoint.sequenceType) && isBlank(waypoint.earliestPickupDateTime)) {
                        missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].earliestPickupDateTime");
                    }
                    if ("PICK".equalsIgnoreCase(waypoint.sequenceType) && isBlank(waypoint.pickUpNumber)) {
                        missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].pickUpNumber");
                    }
                    if ("DROP".equalsIgnoreCase(waypoint.sequenceType) && isBlank(waypoint.earliestDropoffDateTime)) {
                        missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].earliestDropoffDateTime");
                    }
                    if ("DROP".equalsIgnoreCase(waypoint.sequenceType) && isBlank(waypoint.dropOffNumber)) {
                        missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].dropOffNumber");
                    }

                    if (waypoint.commodities == null || waypoint.commodities.isEmpty()) {
                        missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].commodities[0].id");
                        continue;
                    }

                    for (int commodityIndex = 0; commodityIndex < waypoint.commodities.size(); commodityIndex++) {
                        CreateLoadPayload.CommodityRef commodity = waypoint.commodities.get(commodityIndex);
                        if (commodity == null || isBlank(commodity.id)) {
                            missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].commodities[" + commodityIndex + "].id");
                        }
                        if (commodity == null || isBlank(commodity.group)) {
                            missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].commodities[" + commodityIndex + "].group");
                        }
                        if (commodity == null || isBlank(commodity.name)) {
                            missing.add("orders[" + orderIndex + "].waypoints[" + waypointIndex + "].commodities[" + commodityIndex + "].name");
                        }
                    }
                }
            }
        }

        return missing;
    }

    private List<String> omittedPayloadFields(CreateLoadPayload payload) {
        List<String> omitted = new ArrayList<>();
        if (payload == null) {
            omitted.add("payload");
            return omitted;
        }

        addIfBlank(omitted, "size", payload.size);
        addIfBlank(omitted, "driverCombination", payload.driverCombination);
        addIfBlank(omitted, "packaging", payload.packaging);
        addIfBlank(omitted, "equipment", payload.equipment);
        addIfNull(omitted, "reeferMode", payload.reeferMode);
        addIfBlank(omitted, "temperatureSource", payload.temperatureSource);
        if (!hasLoadManagementTeamId(payload.supplementData)) {
            omitted.add("supplementData.loadManagementTeam.id");
        }

        if (payload.orders == null || payload.orders.isEmpty()) {
            omitted.add("orders");
            return omitted;
        }

        for (int orderIndex = 0; orderIndex < payload.orders.size(); orderIndex++) {
            CreateLoadPayload.Order order = payload.orders.get(orderIndex);
            String orderPath = "orders[" + orderIndex + "]";
            if (order == null) {
                omitted.add(orderPath);
                continue;
            }

            addIfNull(omitted, orderPath + ".charges", order.charges);
            addIfNull(omitted, orderPath + ".creditRecipient", order.creditRecipient);
            addIfBlank(omitted, orderPath + ".shipperId", order.shipperId);
            addIfNull(omitted, orderPath + ".company", order.company);
            addIfBlank(omitted, orderPath + ".customerLoadNumber", order.customerLoadNumber);
            addIfBlank(omitted, orderPath + ".pricingType", order.pricingType);
            addIfNull(omitted, orderPath + ".waypoints", order.waypoints);
            addIfNull(omitted, orderPath + ".supplementData", order.supplementData);
            if (!hasLoadManagementTeamId(order.supplementData)) {
                omitted.add(orderPath + ".supplementData.loadManagementTeam.id");
            }

            if (order.company != null) {
                addIfBlank(omitted, orderPath + ".company.id", order.company.id);
            }
            if (order.creditRecipient != null) {
                addIfBlank(omitted, orderPath + ".creditRecipient.userId", order.creditRecipient.userId);
                addIfBlank(omitted, orderPath + ".creditRecipient.commissionPlanId", order.creditRecipient.commissionPlanId);
            }
            if (order.charges != null) {
                omittedChargeFields(omitted, orderPath, order.charges);
            }
            if (order.waypoints != null) {
                omittedWaypointFields(omitted, orderPath, order.waypoints);
            }
        }

        return omitted;
    }

    private void omittedChargeFields(List<String> omitted, String orderPath, List<CreateLoadPayload.Charge> charges) {
        for (int chargeIndex = 0; chargeIndex < charges.size(); chargeIndex++) {
            CreateLoadPayload.Charge charge = charges.get(chargeIndex);
            String chargePath = orderPath + ".charges[" + chargeIndex + "]";
            if (charge == null) {
                omitted.add(chargePath);
                continue;
            }

            addIfNull(omitted, chargePath + ".paymentAddonType", charge.paymentAddonType);
            addIfNull(omitted, chargePath + ".unitPrice", charge.unitPrice);
            addIfNull(omitted, chargePath + ".currency", charge.currency);
            if (charge.paymentAddonType != null) {
                addIfBlank(omitted, chargePath + ".paymentAddonType.id", charge.paymentAddonType.id);
                addIfBlank(omitted, chargePath + ".paymentAddonType.externalId", charge.paymentAddonType.externalId);
            }
            if (charge.currency != null) {
                addIfBlank(omitted, chargePath + ".currency.name", charge.currency.name);
                addIfBlank(omitted, chargePath + ".currency.symbol", charge.currency.symbol);
            }
        }
    }

    private void omittedWaypointFields(List<String> omitted, String orderPath, List<CreateLoadPayload.Waypoint> waypoints) {
        for (int waypointIndex = 0; waypointIndex < waypoints.size(); waypointIndex++) {
            CreateLoadPayload.Waypoint waypoint = waypoints.get(waypointIndex);
            String waypointPath = orderPath + ".waypoints[" + waypointIndex + "]";
            if (waypoint == null) {
                omitted.add(waypointPath);
                continue;
            }

            addIfNull(omitted, waypointPath + ".addressDTO", waypoint.addressDTO);
            addIfBlank(omitted, waypointPath + ".sequenceType", waypoint.sequenceType);
            addIfNull(omitted, waypointPath + ".commodities", waypoint.commodities);
            addIfNull(omitted, waypointPath + ".weight", waypoint.weight);
            addIfNull(omitted, waypointPath + ".palletCount", waypoint.palletCount);
            addIfNull(omitted, waypointPath + ".caseCount", waypoint.caseCount);
            addIfBlank(omitted, waypointPath + ".poNumber", waypoint.poNumber);
            addIfNull(omitted, waypointPath + ".lat", waypoint.lat);
            addIfNull(omitted, waypointPath + ".lon", waypoint.lon);
            addIfBlank(omitted, waypointPath + ".timezone", waypoint.timezone);

            if ("PICK".equalsIgnoreCase(waypoint.sequenceType)) {
                addIfBlank(omitted, waypointPath + ".pickUpNumber", waypoint.pickUpNumber);
                addIfBlank(omitted, waypointPath + ".earliestPickupDateTime", waypoint.earliestPickupDateTime);
            }
            if ("DROP".equalsIgnoreCase(waypoint.sequenceType)) {
                addIfBlank(omitted, waypointPath + ".dropOffNumber", waypoint.dropOffNumber);
                addIfBlank(omitted, waypointPath + ".earliestDropoffDateTime", waypoint.earliestDropoffDateTime);
            }
            if (waypoint.addressDTO != null) {
                omittedAddressFields(omitted, waypointPath + ".addressDTO", waypoint.addressDTO);
            }
            if (waypoint.commodities != null) {
                for (int commodityIndex = 0; commodityIndex < waypoint.commodities.size(); commodityIndex++) {
                    CreateLoadPayload.CommodityRef commodity = waypoint.commodities.get(commodityIndex);
                    String commodityPath = waypointPath + ".commodities[" + commodityIndex + "]";
                    if (commodity == null) {
                        omitted.add(commodityPath);
                    } else {
                        addIfBlank(omitted, commodityPath + ".id", commodity.id);
                        addIfBlank(omitted, commodityPath + ".group", commodity.group);
                        addIfBlank(omitted, commodityPath + ".name", commodity.name);
                    }
                }
            }
        }
    }

    private void omittedAddressFields(List<String> omitted, String addressPath, CreateLoadPayload.AddressDTO address) {
        addIfBlank(omitted, addressPath + ".name", address.name);
        addIfBlank(omitted, addressPath + ".addressLine2", address.addressLine2);
        addIfBlank(omitted, addressPath + ".city", address.city);
        addIfBlank(omitted, addressPath + ".state", address.state);
        addIfBlank(omitted, addressPath + ".zip", address.zip);
        addIfBlank(omitted, addressPath + ".country", address.country);
        addIfBlank(omitted, addressPath + ".type", address.type);
        addIfBlank(omitted, addressPath + ".dst", address.dst);
        addIfBlank(omitted, addressPath + ".timezone", address.timezone);
        addIfNull(omitted, addressPath + ".lat", address.lat);
        addIfNull(omitted, addressPath + ".lon", address.lon);
        addIfNull(omitted, addressPath + ".isValid", address.isValid);
        addIfBlank(omitted, addressPath + ".operationType", address.operationType);
        addIfBlank(omitted, addressPath + ".tenantAddressType", address.tenantAddressType);
        addIfBlank(omitted, addressPath + ".id", address.id);
        addIfNull(omitted, addressPath + ".valid", address.valid);
        addIfBlank(omitted, addressPath + ".line2", address.line2);
        addIfNull(omitted, addressPath + ".addressCity", address.addressCity);
        addIfBlank(omitted, addressPath + ".singleLineAddress", address.singleLineAddress);
        addIfBlank(omitted, addressPath + ".streetAddress", address.streetAddress);
        addIfBlank(omitted, addressPath + ".cityState", address.cityState);
        addIfBlank(omitted, addressPath + ".cityZipState", address.cityZipState);
        if (address.addressCity != null) {
            addIfBlank(omitted, addressPath + ".addressCity.zip", address.addressCity.zip);
            addIfBlank(omitted, addressPath + ".addressCity.name", address.addressCity.name);
            addIfBlank(omitted, addressPath + ".addressCity.state", address.addressCity.state);
            addIfBlank(omitted, addressPath + ".addressCity.country", address.addressCity.country);
        }
    }

    private boolean hasLoadManagementTeamId(Map<String, Object> supplementData) {
        if (supplementData == null || supplementData.isEmpty()) {
            return false;
        }

        Object loadManagementTeam = supplementData.get("loadManagementTeam");
        if (loadManagementTeam instanceof Map<?, ?> loadManagementTeamMap) {
            Object id = loadManagementTeamMap.get("id");
            return id instanceof String stringId && !isBlank(stringId);
        }
        return false;
    }

    private void addIfNull(List<String> omitted, String path, Object value) {
        if (value == null) {
            omitted.add(path);
        }
    }

    private void addIfBlank(List<String> omitted, String path, String value) {
        if (isBlank(value)) {
            omitted.add(path);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim());
    }
}
