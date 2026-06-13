package com.hwyhaul.agent.mapper;

import com.hwyhaul.agent.config.AgentLoadConfig;
import com.hwyhaul.agent.model.CapturedOrdersPayload;
import com.hwyhaul.agent.model.CreateLoadPayload;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadPayloadMapperTest {

    @Test
    void mapUsesConfiguredPickupAndDropoffAddressIds() {
        AgentLoadConfig config = new AgentLoadConfig();
        config.getCompany().setId("configured-company-id");
        config.getAddress().getId().setPickup("pickup-config-id");
        config.getAddress().getId().setDropoff("dropoff-config-id");

        LoadPayloadMapper mapper = new LoadPayloadMapper(config);

        CapturedOrdersPayload capturedPayload = new CapturedOrdersPayload();
        CapturedOrdersPayload.CapturedOrder order = new CapturedOrdersPayload.CapturedOrder();
        order.externalOrderId = "ORDER-1";

        CapturedOrdersPayload.RouteStop pickup = new CapturedOrdersPayload.RouteStop();
        pickup.orderSequenceNumber = 1;
        pickup.sequenceType = "PICK";
        pickup.addressId = "scraped-pickup-id";

        CapturedOrdersPayload.RouteStop dropoff = new CapturedOrdersPayload.RouteStop();
        dropoff.orderSequenceNumber = 2;
        dropoff.sequenceType = "DROP";
        dropoff.addressId = "scraped-dropoff-id";

        order.routeStops = List.of(pickup, dropoff);
        capturedPayload.loads = List.of(order);

        CreateLoadPayload payload = mapper.map(capturedPayload);

        assertEquals("pickup-config-id", payload.orders.get(0).waypoints.get(0).addressDTO.id);
        assertEquals("dropoff-config-id", payload.orders.get(0).waypoints.get(1).addressDTO.id);
    }

    @Test
    void mapPreservesAllCapturedLoads() {
        AgentLoadConfig config = new AgentLoadConfig();
        config.getCompany().setId("configured-company-id");
        LoadPayloadMapper mapper = new LoadPayloadMapper(config);

        CapturedOrdersPayload capturedPayload = new CapturedOrdersPayload();
        CapturedOrdersPayload.CapturedOrder first = new CapturedOrdersPayload.CapturedOrder();
        first.externalOrderId = "ORDER-1";

        CapturedOrdersPayload.CapturedOrder second = new CapturedOrdersPayload.CapturedOrder();
        second.externalOrderId = "ORDER-2";

        capturedPayload.loads = List.of(first, second);

        CreateLoadPayload payload = mapper.map(capturedPayload);

        assertEquals(2, payload.orders.size());
        assertEquals("ORDER-1", payload.orders.get(0).customerLoadNumber);
        assertEquals("ORDER-2", payload.orders.get(1).customerLoadNumber);
    }

    @Test
    void mapSingleCreatesExactlyOneLoad() {
        AgentLoadConfig config = new AgentLoadConfig();
        config.getCompany().setId("configured-company-id");
        LoadPayloadMapper mapper = new LoadPayloadMapper(config);

        CapturedOrdersPayload.CapturedOrder order = new CapturedOrdersPayload.CapturedOrder();
        order.externalOrderId = "ORDER-1";

        CreateLoadPayload payload = mapper.mapSingle(order);

        assertEquals(1, payload.orders.size());
        assertEquals("ORDER-1", payload.orders.get(0).customerLoadNumber);
    }

    @Test
    void mapSinglePopulatesNonNullNestedObjectsOnEveryWaypoint() {
        AgentLoadConfig config = new AgentLoadConfig();
        config.getCompany().setId("configured-company-id");
        config.setCommodityId("commodity-config-id");
        config.setCommodities(List.of(new AgentLoadConfig.Commodity("commodity-config-id", "Produce", "Configured Commodity")));
        LoadPayloadMapper mapper = new LoadPayloadMapper(config);

        CapturedOrdersPayload.CapturedOrder order = new CapturedOrdersPayload.CapturedOrder();
        order.externalOrderId = "ORDER-1";
        order.shipperId = "captured-shipper-id";

        CapturedOrdersPayload.RouteStop pickup = new CapturedOrdersPayload.RouteStop();
        pickup.orderSequenceNumber = 1;
        pickup.sequenceType = "PICK";

        CapturedOrdersPayload.RouteStop dropoff = new CapturedOrdersPayload.RouteStop();
        dropoff.orderSequenceNumber = 2;
        dropoff.sequenceType = "DROP";

        order.routeStops = List.of(pickup, dropoff);

        CreateLoadPayload payload = mapper.mapSingle(order);

        assertNotNull(payload.orders.get(0).waypoints.get(0).commodities);
        assertNotNull(payload.orders.get(0).waypoints.get(1).commodities);
        assertEquals("commodity-config-id", payload.orders.get(0).waypoints.get(0).commodities.get(0).id);
        assertEquals("Produce", payload.orders.get(0).waypoints.get(0).commodities.get(0).group);
        assertEquals("Configured Commodity", payload.orders.get(0).waypoints.get(0).commodities.get(0).name);
        assertEquals("commodity-config-id", payload.orders.get(0).waypoints.get(1).commodities.get(0).id);
        assertNotNull(payload.orders.get(0).waypoints.get(0).addressDTO.addressCity);
        assertNotNull(payload.orders.get(0).waypoints.get(1).addressDTO.addressCity);
        assertNotNull(payload.orders.get(0).waypoints.get(0).addressDTO.lat);
        assertNotNull(payload.orders.get(0).waypoints.get(0).addressDTO.lon);
        assertNotNull(payload.orders.get(0).waypoints.get(0).lat);
        assertNotNull(payload.orders.get(0).waypoints.get(0).lon);
        assertNotNull(payload.orders.get(0).waypoints.get(0).timezone);
        assertNotNull(payload.orders.get(0).waypoints.get(0).weight);
        assertNotNull(payload.orders.get(0).waypoints.get(0).palletCount);
        assertNotNull(payload.orders.get(0).waypoints.get(0).caseCount);
        assertNotNull(payload.orders.get(0).waypoints.get(1).dropOffNumber);
    }

    @Test
    void mapSingleUsesConfiguredShipperIdFromProperties() {
        AgentLoadConfig config = new AgentLoadConfig();
        config.getCompany().setId("configured-company-id");
        config.setShipperId("configured-shipper-id");
        LoadPayloadMapper mapper = new LoadPayloadMapper(config);

        CapturedOrdersPayload.CapturedOrder order = new CapturedOrdersPayload.CapturedOrder();
        order.externalOrderId = "ORDER-1";
        order.shipperId = "captured-shipper-id";

        CreateLoadPayload payload = mapper.mapSingle(order);

        assertEquals("configured-company-id", payload.orders.get(0).company.id);
        assertEquals("configured-shipper-id", payload.orders.get(0).shipperId);
        assertEquals("b6a663d3-569c-4a14-bd87-eb3973029112", payload.orders.get(0).waypoints.get(0).addressDTO.id);
        assertEquals("AMERICAN CLEANING SUPPLY, INC. / INSTOL", payload.orders.get(0).waypoints.get(0).addressDTO.name);
    }

    @Test
    void mapSingleUsesConfiguredLoadApiAddressAndDefaultsMissingDropoffDate() {
        AgentLoadConfig config = new AgentLoadConfig();
        config.getCompany().setId("configured-company-id");
        config.getSchedule().setDefaultDropoffDaysAfterPickup(1);
        LoadPayloadMapper mapper = new LoadPayloadMapper(config);

        CapturedOrdersPayload.CapturedOrder order = new CapturedOrdersPayload.CapturedOrder();
        order.externalOrderId = "ORDER-1";
        order.shipDate = "06/02/2026";
        order.deliveryDate = " ";
        order.pickup = new CapturedOrdersPayload.Stop();
        order.pickup.streetAddress = "11412 Malaga Road Arvin CA 93203 USA GLN: n a Hours:";
        order.dropoff = new CapturedOrdersPayload.Stop();
        order.dropoff.streetAddress = "11588 SE HWY 212 Clackamas OR 97015 USA GLN: n a Hours:";

        CreateLoadPayload payload = mapper.mapSingle(order);

        CreateLoadPayload.AddressDTO pickupAddress = payload.orders.get(0).waypoints.get(0).addressDTO;
        assertEquals("301 Interamerica Blvd", pickupAddress.streetAddress);
        assertEquals("Laredo", pickupAddress.city);
        assertEquals("TX", pickupAddress.state);
        assertEquals("78045", pickupAddress.zip);
        assertEquals("SHARED_PICK", pickupAddress.tenantAddressType);

        CreateLoadPayload.Waypoint dropoff = payload.orders.get(0).waypoints.get(1);
        assertEquals("2500 Westcourt Rd, Aldi - Denton", dropoff.addressDTO.streetAddress);
        assertEquals("Denton", dropoff.addressDTO.city);
        assertEquals("TX", dropoff.addressDTO.state);
        assertEquals("76207", dropoff.addressDTO.zip);
        assertEquals("2026-05-16T08:00:00-05:00[America/Chicago]", dropoff.earliestDropoffDateTime);
    }

    @Test
    void mapSingleUsesConfiguredDefaultsWhenAddressAndDatesAreMissing() {
        AgentLoadConfig config = new AgentLoadConfig();
        config.getCompany().setId("configured-company-id");
        config.getSchedule().setDefaultPickupDateTime("2026-06-10T08:00:00-07:00[America/Los_Angeles]");
        config.getSchedule().setDefaultDropoffDateTime("2026-06-11T08:00:00-07:00[America/Los_Angeles]");
        config.getAddress().getDefaults().getPickup().setName("Fallback pickup");
        config.getAddress().getDefaults().getPickup().setStreetAddress("Fallback pickup street");
        config.getAddress().getDefaults().getPickup().setCity("FallbackPickupCity");
        config.getAddress().getDefaults().getPickup().setState("CA");
        config.getAddress().getDefaults().getPickup().setZip("90001");
        config.getAddress().getDefaults().getDropoff().setName("Fallback dropoff");
        config.getAddress().getDefaults().getDropoff().setStreetAddress("Fallback dropoff street");
        config.getAddress().getDefaults().getDropoff().setCity("FallbackDropoffCity");
        config.getAddress().getDefaults().getDropoff().setState("OR");
        config.getAddress().getDefaults().getDropoff().setZip("97015");
        LoadPayloadMapper mapper = new LoadPayloadMapper(config);

        CapturedOrdersPayload.CapturedOrder order = new CapturedOrdersPayload.CapturedOrder();
        order.externalOrderId = "ORDER-1";

        CreateLoadPayload payload = mapper.mapSingle(order);

        CreateLoadPayload.Waypoint pickup = payload.orders.get(0).waypoints.get(0);
        CreateLoadPayload.Waypoint dropoff = payload.orders.get(0).waypoints.get(1);
        assertEquals("FallbackPickupCity", pickup.addressDTO.city);
        assertEquals("90001", pickup.addressDTO.zip);
        assertEquals("2026-06-10T08:00:00-07:00[America/Los_Angeles]", pickup.earliestPickupDateTime);
        assertEquals("FallbackDropoffCity", dropoff.addressDTO.city);
        assertEquals("97015", dropoff.addressDTO.zip);
        assertEquals("2026-06-11T08:00:00-07:00[America/Los_Angeles]", dropoff.earliestDropoffDateTime);
        assertEquals("D001", dropoff.dropOffNumber);
        assertEquals("LineHa", payload.orders.get(0).charges.get(0).paymentAddonType.externalId);
        assertTrue(payload.supplementData.containsKey("loadManagementTeam"));
        assertTrue(payload.orders.get(0).supplementData.containsKey("loadManagementTeam"));
    }

    @Test
    void mapSingleUsesCapturedOmsrShippingTotals() {
        AgentLoadConfig config = new AgentLoadConfig();
        config.getCompany().setId("configured-company-id");
        LoadPayloadMapper mapper = new LoadPayloadMapper(config);

        CapturedOrdersPayload.CapturedOrder order = new CapturedOrdersPayload.CapturedOrder();
        order.externalOrderId = "ORDER-1";
        order.totalQuantity = "98";
        order.palletCount = "2.00";
        order.cubeCount = "102.8794";
        order.weight = "2940.000";

        CreateLoadPayload payload = mapper.mapSingle(order);

        CreateLoadPayload.Waypoint pickup = payload.orders.get(0).waypoints.get(0);
        assertEquals(new BigDecimal("2940.000"), pickup.weight);
        assertEquals(2, pickup.palletCount);
        assertEquals(98, pickup.caseCount);

        Map<?, ?> shippingTotals = (Map<?, ?>) payload.orders.get(0).supplementData.get("omsrShippingTotals");
        assertEquals("98", shippingTotals.get("totalQuantity"));
        assertEquals("2.00", shippingTotals.get("pallets"));
        assertEquals("102.8794", shippingTotals.get("cubes"));
        assertEquals("2940.000", shippingTotals.get("weight"));
    }

    @Test
    void mapSingleRejectsMalformedDecimalWeight() {
        AgentLoadConfig config = new AgentLoadConfig();
        config.getCompany().setId("configured-company-id");
        config.setDefaultWeight(new BigDecimal("42000"));
        LoadPayloadMapper mapper = new LoadPayloadMapper(config);

        CapturedOrdersPayload.CapturedOrder order = new CapturedOrdersPayload.CapturedOrder();
        order.externalOrderId = "ORDER-1";
        order.weight = "442.9,014 lbs";

        CreateLoadPayload payload = mapper.mapSingle(order);

        CreateLoadPayload.Waypoint pickup = payload.orders.get(0).waypoints.get(0);
        assertEquals(new BigDecimal("42000"), pickup.weight);
    }

    @Test
    void mapSingleUsesCapturedPoNumbersForPickupAndPoFields() {
        AgentLoadConfig config = new AgentLoadConfig();
        config.getCompany().setId("configured-company-id");
        LoadPayloadMapper mapper = new LoadPayloadMapper(config);

        CapturedOrdersPayload.CapturedOrder order = new CapturedOrdersPayload.CapturedOrder();
        order.externalOrderId = "ORDER-1";
        order.poNumbers = List.of("06/09/2026", "10845348", "10845349");

        CreateLoadPayload payload = mapper.mapSingle(order);

        CreateLoadPayload.Waypoint pickup = payload.orders.get(0).waypoints.get(0);
        CreateLoadPayload.Waypoint dropoff = payload.orders.get(0).waypoints.get(1);
        assertEquals("10845348,10845349", pickup.pickUpNumber);
        assertEquals("10845348,10845349", pickup.poNumber);
        assertEquals("10845348,10845349", dropoff.poNumber);
    }
}
