package com.hwyhaul.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateLoadPayload {

    public String size;
    public String driverCombination;
    public String packaging;
    public BigDecimal cargoValueMax;
    public BigDecimal cargoValue;
    public String equipment;
    public List<Order> orders;
    public String type;
    public Integer reeferMode;
    public String temperatureSource;
    public Map<String, Object> supplementData;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Order {
        public List<Charge> charges;
        public CreditRecipient creditRecipient;
        public String shipperId;
        public Company company;
        public String customerLoadNumber;
        public String pricingType;
        public List<Waypoint> waypoints;
        public Map<String, Object> supplementData;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Charge {
        public PaymentAddonType paymentAddonType;
        public BigDecimal unitPrice;
        public int unitCount;
        public Currency currency;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PaymentAddonType {
        public String id;
        public String externalId;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Currency {
        public String name;
        public String symbol;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CreditRecipient {
        public String userId;
        public String commissionPlanId;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Company {
        public String id;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Waypoint {
        public AddressDTO addressDTO;
        public int sequenceNumber;
        public int orderSequenceNumber;
        public String sequenceType;
        public String earliestPickupDateTime;
        public String latestPickupDateTime;
        public String earliestDropoffDateTime;
        public String latestDropoffDateTime;
        public List<CommodityRef> commodities;
        public Double lat;
        public Double lon;
        public BigDecimal weight;
        public Integer palletCount;
        public Integer caseCount;
        public String pickUpNumber;
        public String dropOffNumber;
        public String poNumber;
        public String timezone;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CommodityRef {
        public String id;
        public String group;
        public String name;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AddressDTO {
        public String name;
        public String addressLine1;
        public String addressLine2;
        public String city;
        public String state;
        public String zip;
        public String country;
        public String type;
        public String dst;
        public String timezone;
        public Double lat;
        public Double lon;
        public Boolean isValid;
        public String operationType;
        public String status;
        public String tenantAddressType;
        public String companyId;
        public String id;
        public Boolean valid;
        public String line1;
        public String line2;
        public AddressCity addressCity;
        public String singleLineAddress;
        public String streetAddress;
        public String cityState;
        public String cityZipState;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AddressCity {
        public String zip;
        public String name;
        public String state;
        public String country;
    }
}
