package com.hwyhaul.mcp.browser;

import java.util.List;
import java.util.Map;

public class OrderRow {
    public String orderNo;
    public String shipperId;
    public String customerName;
    public String shipDate;
    public String deliveryDate;
    public String pickupLocation;
    public String pickupDateTime;
    public String dropoffLocation;
    public String dropoffDateTime;
    public String commodity;
    public String palletCount;
    public String weight;
    public List<String> poNumbers;
    public String status;
    public Map<String, String> detailAttributes;
    public String pickupAddressId;
    public String pickupStreetAddress;
    public String pickupCity;
    public String pickupState;
    public String pickupZip;
    public String dropoffAddressId;
    public String dropoffStreetAddress;
    public String dropoffCity;
    public String dropoffState;
    public String dropoffZip;
    public List<RouteStop> routeStops;

    public static class RouteStop extends Stop {
        public Integer orderSequenceNumber;
        public String sequenceType;
        public String pickUpNumber;
        public String dropOffNumber;
        public String poNumber;
        public String earliestPickupDateTime;
        public String latestPickupDateTime;
        public String earliestDropoffDateTime;
        public String latestDropoffDateTime;
        public String weight;
        public String palletCount;
        public String caseCount;
        public String timezone;
    }

    public static class Stop {
        public String addressId;
        public String location;
        public String streetAddress;
        public String city;
        public String state;
        public String zip;
        public String dateTime;
    }
}
