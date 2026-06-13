package com.hwyhaul.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "agent.load")
public class AgentLoadConfig {

    private String size = "TL";
    private String driverCombination = "SOLO";
    private String packaging = "Pallet";
    private BigDecimal cargoValueMax;
    private BigDecimal cargoValue;
    private String equipment = "REEFER";
    private String type;
    private String pricingType = "SPOT";
    private Integer reeferMode = 1;
    private String temperatureSource = "BOL";
    private String paymentAddonTypeId = "96bf6c05-4015-11ef-b506-42010af72332";
    private String paymentAddonTypeExternalId = "LineHa";
    private BigDecimal unitPrice = BigDecimal.valueOf(5000);
    private int unitCount = 1;
    private BigDecimal defaultWeight = BigDecimal.valueOf(42000);
    private Integer defaultPalletCount = 22;
    private Integer defaultCaseCount = 2010;
    private String defaultPickupNumber = "P001";
    private String defaultDropoffNumber = "D001";
    private String defaultPickupPoNumber = "P001";
    private String defaultDropoffPoNumber = "D001";
    private Currency currency = new Currency();
    private CreditRecipient creditRecipient = new CreditRecipient();
    private Company company = new Company();
    private String shipperId = "b0595ecc-1fb6-11ef-b506-42010af7212e";
    private String commodityId = "95288fd1-3dba-11ef-b506-42010af72332";
    private List<Commodity> commodities = defaultCommodities();
    private Address address = new Address();
    private Schedule schedule = new Schedule();
    private SupplementData supplementData = new SupplementData();

    private static List<Commodity> defaultCommodities() {
        List<Commodity> defaults = new ArrayList<>();
        defaults.add(new Commodity("95288c89-3dba-11ef-b506-42010af72332", "Produce", "Bell Peppers"));
        defaults.add(new Commodity("95289265-3dba-11ef-b506-42010af72332", "Produce", "Tomatoes"));
        defaults.add(new Commodity("95288fd1-3dba-11ef-b506-42010af72332", "Produce", "Mini Peppers"));
        defaults.add(new Commodity("95288e26-3dba-11ef-b506-42010af72332", "Produce", "Cucumbers"));
        return defaults;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public String getDriverCombination() {
        return driverCombination;
    }

    public void setDriverCombination(String driverCombination) {
        this.driverCombination = driverCombination;
    }

    public String getPackaging() {
        return packaging;
    }

    public void setPackaging(String packaging) {
        this.packaging = packaging;
    }

    public BigDecimal getCargoValueMax() {
        return cargoValueMax;
    }

    public void setCargoValueMax(BigDecimal cargoValueMax) {
        this.cargoValueMax = cargoValueMax;
    }

    public BigDecimal getCargoValue() {
        return cargoValue;
    }

    public void setCargoValue(BigDecimal cargoValue) {
        this.cargoValue = cargoValue;
    }

    public String getEquipment() {
        return equipment;
    }

    public void setEquipment(String equipment) {
        this.equipment = equipment;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPricingType() {
        return pricingType;
    }

    public void setPricingType(String pricingType) {
        this.pricingType = pricingType;
    }

    public Integer getReeferMode() {
        return reeferMode;
    }

    public void setReeferMode(Integer reeferMode) {
        this.reeferMode = reeferMode;
    }

    public String getTemperatureSource() {
        return temperatureSource;
    }

    public void setTemperatureSource(String temperatureSource) {
        this.temperatureSource = temperatureSource;
    }

    public String getPaymentAddonTypeId() {
        return paymentAddonTypeId;
    }

    public void setPaymentAddonTypeId(String paymentAddonTypeId) {
        this.paymentAddonTypeId = paymentAddonTypeId;
    }

    public String getPaymentAddonTypeExternalId() {
        return paymentAddonTypeExternalId;
    }

    public void setPaymentAddonTypeExternalId(String paymentAddonTypeExternalId) {
        this.paymentAddonTypeExternalId = paymentAddonTypeExternalId;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public int getUnitCount() {
        return unitCount;
    }

    public void setUnitCount(int unitCount) {
        this.unitCount = unitCount;
    }

    public BigDecimal getDefaultWeight() {
        return defaultWeight;
    }

    public void setDefaultWeight(BigDecimal defaultWeight) {
        this.defaultWeight = defaultWeight;
    }

    public Integer getDefaultPalletCount() {
        return defaultPalletCount;
    }

    public void setDefaultPalletCount(Integer defaultPalletCount) {
        this.defaultPalletCount = defaultPalletCount;
    }

    public Integer getDefaultCaseCount() {
        return defaultCaseCount;
    }

    public void setDefaultCaseCount(Integer defaultCaseCount) {
        this.defaultCaseCount = defaultCaseCount;
    }

    public String getDefaultPickupNumber() {
        return defaultPickupNumber;
    }

    public void setDefaultPickupNumber(String defaultPickupNumber) {
        this.defaultPickupNumber = defaultPickupNumber;
    }

    public String getDefaultDropoffNumber() {
        return defaultDropoffNumber;
    }

    public void setDefaultDropoffNumber(String defaultDropoffNumber) {
        this.defaultDropoffNumber = defaultDropoffNumber;
    }

    public String getDefaultPickupPoNumber() {
        return defaultPickupPoNumber;
    }

    public void setDefaultPickupPoNumber(String defaultPickupPoNumber) {
        this.defaultPickupPoNumber = defaultPickupPoNumber;
    }

    public String getDefaultDropoffPoNumber() {
        return defaultDropoffPoNumber;
    }

    public void setDefaultDropoffPoNumber(String defaultDropoffPoNumber) {
        this.defaultDropoffPoNumber = defaultDropoffPoNumber;
    }

    public Currency getCurrency() {
        return currency;
    }

    public void setCurrency(Currency currency) {
        this.currency = currency;
    }

    public CreditRecipient getCreditRecipient() {
        return creditRecipient;
    }

    public void setCreditRecipient(CreditRecipient creditRecipient) {
        this.creditRecipient = creditRecipient;
    }

    public Company getCompany() {
        return company;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public String getShipperId() {
        return shipperId;
    }

    public void setShipperId(String shipperId) {
        this.shipperId = shipperId;
    }

    public String getCommodityId() {
        return commodityId;
    }

    public void setCommodityId(String commodityId) {
        this.commodityId = commodityId;
    }

    public List<Commodity> getCommodities() {
        return commodities;
    }

    public void setCommodities(List<Commodity> commodities) {
        this.commodities = commodities;
    }

    public Address getAddress() {
        return address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    public Schedule getSchedule() {
        return schedule;
    }

    public void setSchedule(Schedule schedule) {
        this.schedule = schedule;
    }

    public SupplementData getSupplementData() {
        return supplementData;
    }

    public void setSupplementData(SupplementData supplementData) {
        this.supplementData = supplementData;
    }

    public static class Address {
        private AddressIds id = new AddressIds();
        private AddressDefaults defaults = new AddressDefaults();

        public AddressIds getId() {
            return id;
        }

        public void setId(AddressIds id) {
            this.id = id;
        }

        public AddressDefaults getDefaults() {
            return defaults;
        }

        public void setDefaults(AddressDefaults defaults) {
            this.defaults = defaults;
        }
    }

    public static class AddressIds {
        private String pickup = "b6a663d3-569c-4a14-bd87-eb3973029112";
        private String dropoff = "48d069cf-233d-43c1-a5dd-79b60e5fe25e";

        public String getPickup() {
            return pickup;
        }

        public void setPickup(String pickup) {
            this.pickup = pickup;
        }

        public String getDropoff() {
            return dropoff;
        }

        public void setDropoff(String dropoff) {
            this.dropoff = dropoff;
        }
    }

    public static class AddressDefaults {
        private StopDefaults pickup = StopDefaults.pickupDefaults();
        private StopDefaults dropoff = StopDefaults.dropoffDefaults();

        public StopDefaults getPickup() {
            return pickup;
        }

        public void setPickup(StopDefaults pickup) {
            this.pickup = pickup;
        }

        public StopDefaults getDropoff() {
            return dropoff;
        }

        public void setDropoff(StopDefaults dropoff) {
            this.dropoff = dropoff;
        }
    }

    public static class StopDefaults {
        private String name = "Configured load address";
        private String addressLine1;
        private String addressLine2 = "Configured load address";
        private String line1;
        private String line2 = "Configured load address";
        private String streetAddress = "Configured load address";
        private String city = "Unknown";
        private String state = "TX";
        private String zip = "00000";
        private String country = "US";
        private String type = "FACILITY";
        private String dst = "1";
        private String timezone = "America/Chicago";
        private Double lat = 0.0;
        private Double lon = 0.0;
        private String operationType = "NOT_SET_UP";
        private String status;
        private String tenantAddressType = "COMPANY";
        private String singleLineAddress;
        private String cityState;
        private String cityZipState;

        public static StopDefaults pickupDefaults() {
            StopDefaults defaults = new StopDefaults();
            defaults.name = "AMERICAN CLEANING SUPPLY, INC. / INSTOL";
            defaults.addressLine2 = "301 Interamerica Blvd";
            defaults.line2 = "301 Interamerica Blvd";
            defaults.streetAddress = "301 Interamerica Blvd";
            defaults.city = "Laredo";
            defaults.state = "TX";
            defaults.zip = "78045";
            defaults.timezone = "America/Chicago";
            defaults.lat = 27.6176514;
            defaults.lon = -99.5278342;
            defaults.tenantAddressType = "SHARED_PICK";
            defaults.singleLineAddress = "301 Interamerica Blvd, Laredo, TX 78045, United States";
            defaults.cityState = "Laredo, TX";
            defaults.cityZipState = "Laredo, TX 78045";
            return defaults;
        }

        public static StopDefaults dropoffDefaults() {
            StopDefaults defaults = new StopDefaults();
            defaults.name = "Denton,TX";
            defaults.addressLine1 = "Aldi - Denton";
            defaults.addressLine2 = "2500 Westcourt Rd";
            defaults.line1 = "Aldi - Denton";
            defaults.line2 = "2500 Westcourt Rd";
            defaults.streetAddress = "2500 Westcourt Rd, Aldi - Denton";
            defaults.city = "Denton";
            defaults.state = "TX";
            defaults.zip = "76207";
            defaults.timezone = "America/Chicago";
            defaults.lat = 33.1914791;
            defaults.lon = -97.19236;
            defaults.tenantAddressType = "COMPANY";
            defaults.singleLineAddress = "Aldi - Denton, 2500 Westcourt Rd, Denton, TX 76207, United States";
            defaults.cityState = "Denton, TX";
            defaults.cityZipState = "Denton, TX 76207";
            return defaults;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAddressLine1() {
            return addressLine1;
        }

        public void setAddressLine1(String addressLine1) {
            this.addressLine1 = addressLine1;
        }

        public String getAddressLine2() {
            return addressLine2;
        }

        public void setAddressLine2(String addressLine2) {
            this.addressLine2 = addressLine2;
        }

        public String getLine1() {
            return line1;
        }

        public void setLine1(String line1) {
            this.line1 = line1;
        }

        public String getLine2() {
            return line2;
        }

        public void setLine2(String line2) {
            this.line2 = line2;
        }

        public String getStreetAddress() {
            return streetAddress;
        }

        public void setStreetAddress(String streetAddress) {
            this.streetAddress = streetAddress;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        public String getZip() {
            return zip;
        }

        public void setZip(String zip) {
            this.zip = zip;
        }

        public String getCountry() {
            return country;
        }

        public void setCountry(String country) {
            this.country = country;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getDst() {
            return dst;
        }

        public void setDst(String dst) {
            this.dst = dst;
        }

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }

        public Double getLat() {
            return lat;
        }

        public void setLat(Double lat) {
            this.lat = lat;
        }

        public Double getLon() {
            return lon;
        }

        public void setLon(Double lon) {
            this.lon = lon;
        }

        public String getOperationType() {
            return operationType;
        }

        public void setOperationType(String operationType) {
            this.operationType = operationType;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getTenantAddressType() {
            return tenantAddressType;
        }

        public void setTenantAddressType(String tenantAddressType) {
            this.tenantAddressType = tenantAddressType;
        }

        public String getSingleLineAddress() {
            return singleLineAddress;
        }

        public void setSingleLineAddress(String singleLineAddress) {
            this.singleLineAddress = singleLineAddress;
        }

        public String getCityState() {
            return cityState;
        }

        public void setCityState(String cityState) {
            this.cityState = cityState;
        }

        public String getCityZipState() {
            return cityZipState;
        }

        public void setCityZipState(String cityZipState) {
            this.cityZipState = cityZipState;
        }
    }

    public static class Schedule {
        private String defaultTimezone = "America/Chicago";
        private String defaultPickupDateTime = "2026-05-15T08:00:00-05:00[America/Chicago]";
        private String defaultDropoffDateTime = "2026-05-16T08:00:00-05:00[America/Chicago]";
        private long defaultPickupDaysFromNow = 0;
        private long defaultDropoffDaysAfterPickup = 1;

        public String getDefaultTimezone() {
            return defaultTimezone;
        }

        public void setDefaultTimezone(String defaultTimezone) {
            this.defaultTimezone = defaultTimezone;
        }

        public String getDefaultPickupDateTime() {
            return defaultPickupDateTime;
        }

        public void setDefaultPickupDateTime(String defaultPickupDateTime) {
            this.defaultPickupDateTime = defaultPickupDateTime;
        }

        public String getDefaultDropoffDateTime() {
            return defaultDropoffDateTime;
        }

        public void setDefaultDropoffDateTime(String defaultDropoffDateTime) {
            this.defaultDropoffDateTime = defaultDropoffDateTime;
        }

        public long getDefaultPickupDaysFromNow() {
            return defaultPickupDaysFromNow;
        }

        public void setDefaultPickupDaysFromNow(long defaultPickupDaysFromNow) {
            this.defaultPickupDaysFromNow = defaultPickupDaysFromNow;
        }

        public long getDefaultDropoffDaysAfterPickup() {
            return defaultDropoffDaysAfterPickup;
        }

        public void setDefaultDropoffDaysAfterPickup(long defaultDropoffDaysAfterPickup) {
            this.defaultDropoffDaysAfterPickup = defaultDropoffDaysAfterPickup;
        }
    }

    public static class SupplementData {
        private String loadManagementTeamId = "ff123497-55d2-11ef-b506-42010af72332";
        private String sourceSystem = "OMSR";
        private String sourceType = "OMSR_LOAD";
        private String defaultSourceOrderId = "UNKNOWN";
        private String defaultCustomerName = "UNKNOWN";
        private String defaultPageUrl = "UNKNOWN";

        public String getLoadManagementTeamId() {
            return loadManagementTeamId;
        }

        public void setLoadManagementTeamId(String loadManagementTeamId) {
            this.loadManagementTeamId = loadManagementTeamId;
        }

        public String getSourceSystem() {
            return sourceSystem;
        }

        public void setSourceSystem(String sourceSystem) {
            this.sourceSystem = sourceSystem;
        }

        public String getSourceType() {
            return sourceType;
        }

        public void setSourceType(String sourceType) {
            this.sourceType = sourceType;
        }

        public String getDefaultSourceOrderId() {
            return defaultSourceOrderId;
        }

        public void setDefaultSourceOrderId(String defaultSourceOrderId) {
            this.defaultSourceOrderId = defaultSourceOrderId;
        }

        public String getDefaultCustomerName() {
            return defaultCustomerName;
        }

        public void setDefaultCustomerName(String defaultCustomerName) {
            this.defaultCustomerName = defaultCustomerName;
        }

        public String getDefaultPageUrl() {
            return defaultPageUrl;
        }

        public void setDefaultPageUrl(String defaultPageUrl) {
            this.defaultPageUrl = defaultPageUrl;
        }
    }

    public static class Commodity {
        private String id;
        private String group;
        private String name;

        public Commodity() {
        }

        public Commodity(String id, String group, String name) {
            this.id = id;
            this.group = group;
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class Currency {
        private String name = "USD";
        private String symbol = "US$";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSymbol() {
            return symbol;
        }

        public void setSymbol(String symbol) {
            this.symbol = symbol;
        }
    }

    public static class CreditRecipient {
        private String userId = "90ddef76-5889-11ef-b506-42010af72332";
        private String commissionPlanId = "d2aef315-5889-11ef-b506-42010af72332";

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getCommissionPlanId() {
            return commissionPlanId;
        }

        public void setCommissionPlanId(String commissionPlanId) {
            this.commissionPlanId = commissionPlanId;
        }
    }

    public static class Company {
        private String id = "b3b74b30-5cfc-4301-bfe3-c7c5655c658b";

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }
}
