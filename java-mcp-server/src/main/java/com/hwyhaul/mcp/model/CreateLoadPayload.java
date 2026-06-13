package com.hwyhaul.mcp.model;

import java.util.List;

public class CreateLoadPayload {

    public String xHhToken;
    public List<Load> loads;

    public static class Load {
        public String externalOrderId;
        public String shipperId;
        public String customerName;
        public String shipDate;
        public String deliveryDate;
        public Stop pickup;
        public Stop dropoff;
        public List<RouteStop> routeStops;
        public String commodity;
        public String palletCount;
        public String weight;
        public List<String> poNumbers;
        public String status;
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
}
