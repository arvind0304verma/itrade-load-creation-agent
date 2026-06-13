package com.hwyhaul.agent.model;

import java.util.List;
import java.util.Map;

public class CapturedOrdersPayload {

    public String omsrToken;
    // HwyHaul token used to authenticate the Load API call.
    public String xHhToken;
    public List<CapturedOrder> loads;

    public static class CapturedOrder {
        public String externalOrderId;
        public String shipperId;
        public String customerName;
        public String shipDate;
        public String deliveryDate;
        public Stop pickup;
        public Stop dropoff;
        public String commodity;
        public String totalQuantity;
        public String palletCount;
        public String cubeCount;
        public String weight;
        public String caseCount;
        public List<String> poNumbers;
        public String status;
        public String pickupNumber;
        public String pageUrl;
        public String rate;
        public Map<String, String> detailAttributes;
        public List<RouteStop> routeStops;
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
