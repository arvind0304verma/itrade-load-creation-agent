package com.hwyhaul.agent.mapper;

import com.hwyhaul.agent.config.AgentLoadConfig;
import com.hwyhaul.agent.model.CapturedOrdersPayload;
import com.hwyhaul.agent.model.CreateLoadPayload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Year;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LoadPayloadMapper {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("America/New_York");
    private static final ZoneId PACIFIC_ZONE = ZoneId.of("America/Los_Angeles");
    private static final LocalTime TIME_OF_DAY_8AM = LocalTime.of(8, 0);
    private static final Pattern SCRAPED_DATE_TIME = Pattern.compile(
            "^[A-Za-z]{3}\\s+([A-Za-z]{3})\\s+(\\d{1,2})\\s+(?:\\u2022|-)\\s+(\\d{1,2}:\\d{2}\\s+[AP]M),\\s*([A-Za-z]{2,4})$"
    );
    private static final Pattern ADDRESS_WITH_STATE_ZIP = Pattern.compile(
            "^(?<prefix>.+?)\\s+(?<state>[A-Z]{2})\\s+(?<zip>\\d{5}(?:-\\d{4})?)(?:\\s+(?:USA|US|UNITED STATES))?.*$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern STREET_AND_CITY = Pattern.compile(
            "^(?<street>\\d{1,8}\\s+.*\\b(?:ALY|AVE|AVENUE|BLVD|BOULEVARD|CIR|CIRCLE|CT|COURT|DR|DRIVE|HWY\\s+\\d+|HWY|HIGHWAY\\s+\\d+|HIGHWAY|LN|LANE|LOOP|PKWY|PARKWAY|PL|PLACE|RD|ROAD|ST|STREET|TER|TERRACE|TRL|TRAIL|WAY)\\b)\\s+(?<city>[A-Za-z][A-Za-z .'-]*)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final DateTimeFormatter SCRAPED_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy MMM d h:mm a", Locale.US);
    private static final List<DateTimeFormatter> LOCAL_DATE_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("M/d/uuuu h:mm a", Locale.US),
            DateTimeFormatter.ofPattern("MM/dd/uuuu h:mm a", Locale.US),
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.US),
            DateTimeFormatter.ofPattern("MMM d, uuuu h:mm a", Locale.US),
            DateTimeFormatter.ofPattern("MMMM d, uuuu h:mm a", Locale.US)
    );
    private static final List<DateTimeFormatter> LOCAL_DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("M/d/uuuu", Locale.US),
            DateTimeFormatter.ofPattern("MM/dd/uuuu", Locale.US),
            DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US),
            DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US)
    );

    private final AgentLoadConfig config;

    public LoadPayloadMapper(AgentLoadConfig config) {
        this.config = config;
    }

    public CreateLoadPayload map(CapturedOrdersPayload capturedPayload) {
        validateRequiredIds(capturedPayload == null ? null : capturedPayload.loads);

        CreateLoadPayload payload = newPayload();

        if (capturedPayload == null || capturedPayload.loads == null) {
            payload.orders = List.of();
            return payload;
        }

        payload.orders = capturedPayload.loads.stream()
                .map(this::mapOrder)
                .toList();
        return payload;
    }

    public CreateLoadPayload mapSingle(CapturedOrdersPayload.CapturedOrder capturedOrder) {
        validateRequiredIds(capturedOrder == null ? null : List.of(capturedOrder));

        CreateLoadPayload payload = newPayload();
        payload.orders = capturedOrder == null ? List.of() : List.of(mapOrder(capturedOrder));
        return payload;
    }

    private CreateLoadPayload newPayload() {
        CreateLoadPayload payload = new CreateLoadPayload();
        payload.size = firstNonBlank(config.getSize(), "TL");
        payload.driverCombination = firstNonBlank(config.getDriverCombination(), "SOLO");
        payload.packaging = firstNonBlank(config.getPackaging(), "Pallet");
        payload.cargoValueMax = config.getCargoValueMax();
        payload.cargoValue = config.getCargoValue();
        payload.equipment = firstNonBlank(config.getEquipment(), "REEFER");
        payload.type = blankToNull(config.getType());
        payload.reeferMode = config.getReeferMode();
        payload.temperatureSource = blankToNull(config.getTemperatureSource());
        payload.supplementData = mapSupplementData();
        return payload;
    }

    private CreateLoadPayload.Order mapOrder(CapturedOrdersPayload.CapturedOrder capturedOrder) {
        String companyId = resolveCompanyId();
        CreateLoadPayload.Order order = new CreateLoadPayload.Order();
        order.charges = List.of(mapCharge(capturedOrder));
        order.creditRecipient = mapCreditRecipient();
        // Temporary backend contract alignment: shipperId is sourced from config until OMSR-to-LOAD mapping is confirmed.
        order.shipperId = firstNonBlank(config.getShipperId(), capturedOrder == null ? null : capturedOrder.shipperId);
        order.company = mapCompany(companyId);
        order.customerLoadNumber = firstNonBlank(capturedOrder == null ? null : capturedOrder.externalOrderId, defaultSourceOrderId());
        order.pricingType = firstNonBlank(config.getPricingType(), "SPOT");
        order.waypoints = mapWaypoints(capturedOrder, companyId);
        order.supplementData = mapOrderSupplementData(capturedOrder);
        return order;
    }

    private CreateLoadPayload.Charge mapCharge(CapturedOrdersPayload.CapturedOrder capturedOrder) {
        CreateLoadPayload.Charge charge = new CreateLoadPayload.Charge();
        charge.paymentAddonType = new CreateLoadPayload.PaymentAddonType();
        charge.paymentAddonType.id = config.getPaymentAddonTypeId();
        charge.paymentAddonType.externalId = firstNonBlank(config.getPaymentAddonTypeExternalId(), config.getPaymentAddonTypeId());
        BigDecimal scrapedRate = parseDecimal(capturedOrder == null ? null : capturedOrder.rate);
        charge.unitPrice = firstNonNull(scrapedRate, config.getUnitPrice());
        charge.unitCount = config.getUnitCount();
        charge.currency = new CreateLoadPayload.Currency();
        charge.currency.name = firstNonBlank(config.getCurrency() == null ? null : config.getCurrency().getName(), "USD");
        charge.currency.symbol = firstNonBlank(config.getCurrency() == null ? null : config.getCurrency().getSymbol(), "US$");
        return charge;
    }

    private CreateLoadPayload.CreditRecipient mapCreditRecipient() {
        CreateLoadPayload.CreditRecipient creditRecipient = new CreateLoadPayload.CreditRecipient();
        creditRecipient.userId = config.getCreditRecipient() == null ? null : config.getCreditRecipient().getUserId();
        creditRecipient.commissionPlanId = config.getCreditRecipient() == null ? null : config.getCreditRecipient().getCommissionPlanId();
        return creditRecipient;
    }

    private CreateLoadPayload.Company mapCompany(String companyId) {
        CreateLoadPayload.Company company = new CreateLoadPayload.Company();
        company.id = companyId;
        return company;
    }

    private Map<String, Object> mapSupplementData() {
        Map<String, Object> supplementData = new LinkedHashMap<>();
        String loadManagementTeamId = firstNonBlank(
                supplementDataConfig().getLoadManagementTeamId(),
                "ff123497-55d2-11ef-b506-42010af72332"
        );
        Map<String, Object> loadManagementTeam = new LinkedHashMap<>();
        loadManagementTeam.put("id", loadManagementTeamId);
        supplementData.put("loadManagementTeam", loadManagementTeam);
        return supplementData;
    }

    private Map<String, Object> mapOrderSupplementData(CapturedOrdersPayload.CapturedOrder capturedOrder) {
        Map<String, Object> supplementData = mapSupplementData();
        Map<String, Object> omsrShippingTotals = new LinkedHashMap<>();
        putIfPresent(omsrShippingTotals, "totalQuantity", capturedOrder == null ? null : capturedOrder.totalQuantity);
        putIfPresent(omsrShippingTotals, "pallets", capturedOrder == null ? null : capturedOrder.palletCount);
        putIfPresent(omsrShippingTotals, "cubes", capturedOrder == null ? null : capturedOrder.cubeCount);
        putIfPresent(omsrShippingTotals, "weight", capturedOrder == null ? null : capturedOrder.weight);
        putIfPresent(omsrShippingTotals, "caseCount", capturedOrder == null ? null : capturedOrder.caseCount);
        if (!omsrShippingTotals.isEmpty()) {
            supplementData.put("omsrShippingTotals", omsrShippingTotals);
        }
        return supplementData;
    }

    private void putIfPresent(Map<String, Object> values, String key, String value) {
        String normalized = blankToNull(value);
        if (normalized != null) {
            values.put(key, normalized);
        }
    }

    private CreateLoadPayload.Waypoint mapPickup(CapturedOrdersPayload.CapturedOrder capturedOrder, String companyId) {
        return mapWaypoint(capturedOrder, capturedOrder.pickup, 1, "PICK", true, companyId);
    }

    private CreateLoadPayload.Waypoint mapDropoff(CapturedOrdersPayload.CapturedOrder capturedOrder, String companyId) {
        return mapWaypoint(capturedOrder, capturedOrder.dropoff, 2, "DROP", false, companyId);
    }

    private List<CreateLoadPayload.Waypoint> mapWaypoints(CapturedOrdersPayload.CapturedOrder capturedOrder, String companyId) {
        if (capturedOrder == null || capturedOrder.routeStops == null || capturedOrder.routeStops.size() < 2) {
            return List.of(mapPickup(capturedOrder, companyId), mapDropoff(capturedOrder, companyId));
        }

        List<CapturedOrdersPayload.RouteStop> routeStops = capturedOrder.routeStops.stream()
                .filter(routeStop -> routeStop != null)
                .sorted(Comparator.comparing(
                        (CapturedOrdersPayload.RouteStop routeStop) -> routeStop.orderSequenceNumber,
                        Comparator.nullsLast(Integer::compareTo)))
                .toList();

        if (routeStops.size() < 2) {
            return List.of(mapPickup(capturedOrder, companyId), mapDropoff(capturedOrder, companyId));
        }

        List<CreateLoadPayload.Waypoint> waypoints = new ArrayList<>(routeStops.size());
        for (int index = 0; index < routeStops.size(); index++) {
            waypoints.add(mapRouteWaypoint(capturedOrder, routeStops.get(index), index, companyId));
        }
        return waypoints;
    }

    private CreateLoadPayload.Waypoint mapRouteWaypoint(
            CapturedOrdersPayload.CapturedOrder capturedOrder,
            CapturedOrdersPayload.RouteStop routeStop,
            int index,
            String companyId
    ) {
        boolean pickup = isPickupSequence(routeStop.sequenceType);
        boolean dropoff = isDropoffSequence(routeStop.sequenceType);
        if (!pickup && !dropoff) {
            pickup = index == 0;
            dropoff = !pickup;
        }

        String sequenceType = normalizeSequenceType(routeStop.sequenceType, pickup, dropoff);
        int sequenceNumber = routeStop.orderSequenceNumber == null ? index + 1 : routeStop.orderSequenceNumber;
        return mapWaypoint(capturedOrder, routeStop, sequenceNumber, sequenceType, pickup, companyId);
    }

    private CreateLoadPayload.Waypoint mapWaypoint(
            CapturedOrdersPayload.CapturedOrder capturedOrder,
            CapturedOrdersPayload.Stop stop,
            int sequenceNumber,
            String sequenceType,
            boolean pickup,
            String companyId
    ) {
        CreateLoadPayload.Waypoint waypoint = new CreateLoadPayload.Waypoint();
        CapturedOrdersPayload.RouteStop routeStop = stop instanceof CapturedOrdersPayload.RouteStop
                ? (CapturedOrdersPayload.RouteStop) stop
                : null;
        ZoneId stopZone = timezoneForStop(pickup, routeStop == null ? null : routeStop.timezone);
        waypoint.addressDTO = mapAddress(stop, pickup, companyId);
        if (waypoint.addressDTO != null) {
            waypoint.addressDTO.timezone = stopZone.getId();
            waypoint.lat = waypoint.addressDTO.lat;
            waypoint.lon = waypoint.addressDTO.lon;
            waypoint.timezone = waypoint.addressDTO.timezone;
        }
        waypoint.sequenceNumber = sequenceNumber;
        waypoint.orderSequenceNumber = sequenceNumber;
        waypoint.sequenceType = sequenceType;
        waypoint.commodities = mapCommodities();

        String stopWeight = routeStop == null ? null : routeStop.weight;
        String stopPalletCount = routeStop == null ? null : routeStop.palletCount;
        String stopCaseCount = routeStop == null ? null : routeStop.caseCount;
        String stopPoNumber = routeStop == null ? null : routeStop.poNumber;
        String stopPickupNumber = routeStop == null ? null : routeStop.pickUpNumber;
        String stopDropoffNumber = routeStop == null ? null : routeStop.dropOffNumber;
        String omsrPoNumbers = joinedPoNumbers(capturedOrder, stopPoNumber);

        waypoint.weight = firstNonNull(
                parseDecimal(firstNonBlank(stopWeight, capturedOrder == null ? null : capturedOrder.weight)),
                config.getDefaultWeight()
        );
        waypoint.palletCount = firstNonNull(
                parseInteger(firstNonBlank(stopPalletCount, capturedOrder == null ? null : capturedOrder.palletCount)),
                config.getDefaultPalletCount()
        );
        waypoint.caseCount = firstNonNull(
                parseInteger(firstNonBlank(
                        stopCaseCount,
                        capturedOrder == null ? null : capturedOrder.caseCount,
                        capturedOrder == null ? null : capturedOrder.totalQuantity)),
                config.getDefaultCaseCount()
        );

        if (pickup) {
            String dateTime = pickupDateTime(capturedOrder, stop, stopZone);
            waypoint.earliestPickupDateTime = dateTime;
            String pickupNumber = firstNonBlank(
                    omsrPoNumbers,
                    stopPickupNumber,
                    capturedOrder == null ? null : capturedOrder.pickupNumber,
                    config.getDefaultPickupNumber()
            );
            waypoint.pickUpNumber = pickupNumber;
            waypoint.poNumber = firstNonBlank(omsrPoNumbers, firstValidPoNumber(stopPoNumber, config.getDefaultPickupPoNumber()));
        } else {
            waypoint.earliestDropoffDateTime = dropoffDateTime(capturedOrder, stop, stopZone);
            waypoint.dropOffNumber = firstNonBlank(config.getDefaultDropoffNumber(), stopDropoffNumber);
            waypoint.poNumber = firstNonBlank(omsrPoNumbers, firstValidPoNumber(stopPoNumber, config.getDefaultDropoffPoNumber()));
        }

        return waypoint;
    }

    private boolean isPickupSequence(String sequenceType) {
        if (isBlank(sequenceType)) {
            return false;
        }

        return sequenceType.toUpperCase(Locale.US).contains("PICK");
    }

    private boolean isDropoffSequence(String sequenceType) {
        if (isBlank(sequenceType)) {
            return false;
        }

        return sequenceType.toUpperCase(Locale.US).contains("DROP");
    }

    private String normalizeSequenceType(String sequenceType, boolean pickup, boolean dropoff) {
        if (!isBlank(sequenceType)) {
            String normalized = sequenceType.trim().toUpperCase(Locale.US);
            if (normalized.contains("PICK")) {
                return "PICK";
            }
            if (normalized.contains("DROP")) {
                return "DROP";
            }
            return normalized;
        }
        if (pickup && !dropoff) {
            return "PICK";
        }
        if (dropoff && !pickup) {
            return "DROP";
        }
        return null;
    }

    private CreateLoadPayload.CommodityRef mapCommodity() {
        CreateLoadPayload.CommodityRef commodity = new CreateLoadPayload.CommodityRef();
        commodity.id = config.getCommodityId();
        return commodity;
    }

    private List<CreateLoadPayload.CommodityRef> mapCommodities() {
        if (config.getCommodities() == null || config.getCommodities().isEmpty()) {
            return List.of(mapCommodity());
        }

        List<CreateLoadPayload.CommodityRef> commodities = config.getCommodities().stream()
                .filter(commodity -> commodity != null && !isBlank(commodity.getId()))
                .map(this::mapCommodity)
                .toList();
        return commodities.isEmpty() ? List.of(mapCommodity()) : commodities;
    }

    private CreateLoadPayload.CommodityRef mapCommodity(AgentLoadConfig.Commodity configuredCommodity) {
        CreateLoadPayload.CommodityRef commodity = new CreateLoadPayload.CommodityRef();
        commodity.id = configuredCommodity.getId();
        commodity.group = blankToNull(configuredCommodity.getGroup());
        commodity.name = blankToNull(configuredCommodity.getName());
        return commodity;
    }

    private CreateLoadPayload.AddressDTO mapAddress(CapturedOrdersPayload.Stop stop, boolean pickup, String companyId) {
        AgentLoadConfig.StopDefaults defaults = addressDefaults(pickup);
        String city = firstNonBlank(defaults.getCity(), stop == null ? null : stop.city);
        String state = firstNonBlank(defaults.getState(), stop == null ? null : stop.state);
        String zip = firstNonBlank(defaults.getZip(), stop == null ? null : stop.zip);
        String resolvedStreetAddress = firstNonBlank(defaults.getStreetAddress(), defaults.getAddressLine2(), defaults.getLine2());
        String singleLineAddress = firstNonBlank(defaults.getSingleLineAddress(), singleLineAddress(resolvedStreetAddress, city, state, zip));
        CreateLoadPayload.AddressDTO address = new CreateLoadPayload.AddressDTO();
        address.name = firstNonBlank(defaults.getName(), singleLineAddress, resolvedStreetAddress);
        address.addressLine1 = blankToNull(defaults.getAddressLine1());
        address.addressLine2 = firstNonBlank(defaults.getAddressLine2(), defaults.getLine2(), resolvedStreetAddress);
        address.city = city;
        address.state = state;
        address.zip = zip;
        address.country = firstNonBlank(defaults.getCountry(), "US");
        address.type = firstNonBlank(defaults.getType(), "FACILITY");
        address.dst = firstNonBlank(defaults.getDst(), "1");
        address.lat = defaults.getLat();
        address.lon = defaults.getLon();
        address.isValid = false;
        address.operationType = firstNonBlank(defaults.getOperationType(), "NOT_SET_UP");
        address.status = blankToNull(defaults.getStatus());
        address.tenantAddressType = firstNonBlank(defaults.getTenantAddressType(), "COMPANY");
        address.id = pickup ? pickupAddressId() : dropoffAddressId();
        address.valid = false;
        address.line1 = blankToNull(defaults.getLine1());
        address.line2 = firstNonBlank(defaults.getLine2(), defaults.getAddressLine2(), resolvedStreetAddress);
        address.singleLineAddress = singleLineAddress;
        address.streetAddress = resolvedStreetAddress;
        address.addressCity = new CreateLoadPayload.AddressCity();
        address.addressCity.name = address.city;
        address.addressCity.state = address.state;
        address.addressCity.zip = address.zip;
        address.addressCity.country = "US";
        if (address.city != null || address.state != null || address.zip != null) {
            address.cityState = firstNonBlank(defaults.getCityState(), joinNonBlank(", ", address.city, address.state));
            address.cityZipState = firstNonBlank(defaults.getCityZipState(), joinCityStateZip(address.city, address.state, address.zip));
        }
        return address;
    }

    private AddressParts parseAddressParts(String value) {
        String cleaned = normalizeAddressLine(value);
        if (cleaned == null) {
            return null;
        }

        Matcher addressMatcher = ADDRESS_WITH_STATE_ZIP.matcher(cleaned);
        if (!addressMatcher.matches()) {
            return null;
        }

        String prefix = addressMatcher.group("prefix").trim();
        String state = addressMatcher.group("state").toUpperCase(Locale.US);
        String zip = addressMatcher.group("zip");
        Matcher streetMatcher = STREET_AND_CITY.matcher(prefix);
        if (streetMatcher.matches()) {
            return new AddressParts(
                    streetMatcher.group("street").trim(),
                    streetMatcher.group("city").trim(),
                    state,
                    zip
            );
        }

        int lastSpace = prefix.lastIndexOf(' ');
        if (lastSpace <= 0 || lastSpace == prefix.length() - 1) {
            return null;
        }
        return new AddressParts(
                prefix.substring(0, lastSpace).trim(),
                prefix.substring(lastSpace + 1).trim(),
                state,
                zip
        );
    }

    private String normalizeAddressLine(String value) {
        if (isBlank(value)) {
            return null;
        }

        String cleaned = value.replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace('\u2007', ' ')
                .replaceAll("[\\p{Z}\\s]+", " ")
                .replaceAll("(?i)\\s+GLN\\s*:.*$", "")
                .replaceAll("(?i)\\s+Hours\\s*:.*$", "")
                .replaceAll("\\s*,\\s*", " ")
                .trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private String singleLineAddress(String streetAddress, String city, String state, String zip) {
        String cityStateZip = joinCityStateZip(city, state, zip);
        if (isBlank(streetAddress)) {
            return cityStateZip;
        }
        if (isBlank(cityStateZip)) {
            return streetAddress;
        }
        return streetAddress.trim() + ", " + cityStateZip + ", United States";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }

        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private <T> T firstNonNull(T first, T second) {
        return first == null ? second : first;
    }

    private String normalizeStopValue(String value) {
        if (isBlank(value)) {
            return null;
        }

        String normalized = value.replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace('\u2007', ' ')
                .replaceAll("[\\p{Z}\\s]+", " ")
                .trim()
                .toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]", "");

        return normalized.isBlank()
                || normalized.equals("swh")
                || normalized.equals("rwh")
                || normalized.equals("swr")
                || normalized.equals("pickup")
                || normalized.equals("dropoff")
                || normalized.equals("delivery")
                || normalized.equals("origin")
                || normalized.equals("destination")
                || normalized.equals("shipper")
                || normalized.equals("receiver")
                || normalized.equals("shipfrom")
                ? null
                : value;
    }

    private String preferStreetAddress(String current, String candidate) {
        if (isBlank(candidate)) {
            return current;
        }
        if (isBlank(current)) {
            return candidate;
        }

        boolean currentLooksAddress = looksLikeAddress(current);
        boolean candidateLooksAddress = looksLikeAddress(candidate);
        if (candidateLooksAddress && !currentLooksAddress) {
            return candidate;
        }
        if (!candidateLooksAddress && currentLooksAddress) {
            return current;
        }
        return current;
    }

    private String joinCityStateZip(String city, String state, String zip) {
        if (isBlank(city) && isBlank(state) && isBlank(zip)) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        if (!isBlank(city)) {
            builder.append(city.trim());
        }
        if (!isBlank(state)) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(state.trim());
        }
        if (!isBlank(zip)) {
            if (builder.length() > 0) {
                builder.append(" ");
            }
            builder.append(zip.trim());
        }
        return builder.toString();
    }

    private boolean looksLikeAddress(String value) {
        if (isBlank(value)) {
            return false;
        }

        String normalized = value.replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace('\u2007', ' ')
                .replaceAll("[\\p{Z}\\s]+", " ")
                .replaceAll("\\s*/\\s*", " ")
                .trim();

        return normalized.matches("(?i)^\\d{1,8}\\s+.+\\b[A-Z]{2}\\s+\\d{5}(?:-\\d{4})?(?:\\s+(?:United States|USA|US))?$")
                || normalized.matches("(?i)^\\d{1,8}\\s+.+,\\s+.+,\\s+[A-Z]{2}\\s+\\d{5}(?:-\\d{4})?(?:\\s+(?:United States|USA|US))?$");
    }

    private String joinNonBlank(String delimiter, String... values) {
        List<String> nonBlankValues = java.util.Arrays.stream(values)
                .filter(value -> !isBlank(value))
                .toList();
        return nonBlankValues.isEmpty() ? null : String.join(delimiter, nonBlankValues);
    }

    private String pickupDateTime(CapturedOrdersPayload.CapturedOrder capturedOrder, CapturedOrdersPayload.Stop stop, ZoneId zone) {
        String scrapedDate = firstNonBlank(
                stopDateTimeValue(stop, true),
                capturedOrder == null ? null : capturedOrder.shipDate
        );
        String fromScrapedDate = dateAt8AmPacific(scrapedDate);
        if (fromScrapedDate != null) {
            return fromScrapedDate;
        }

        String configuredDateTime = normalizeDateTime(scheduleConfig().getDefaultPickupDateTime(), zone);
        if (configuredDateTime != null) {
            return configuredDateTime;
        }

        return LocalDate.now(zone)
                .plusDays(scheduleConfig().getDefaultPickupDaysFromNow())
                .atStartOfDay(zone)
                .format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
    }

    private String dropoffDateTime(CapturedOrdersPayload.CapturedOrder capturedOrder, CapturedOrdersPayload.Stop stop, ZoneId zone) {
        String scrapedDate = firstNonBlank(
                stopDateTimeValue(stop, false),
                capturedOrder == null ? null : capturedOrder.deliveryDate
        );
        String fromScrapedDate = dateAt8AmPacific(scrapedDate);
        if (fromScrapedDate != null) {
            return fromScrapedDate;
        }

        String configuredDateTime = normalizeDateTime(scheduleConfig().getDefaultDropoffDateTime(), zone);
        if (configuredDateTime != null) {
            return configuredDateTime;
        }

        ZoneId pickupZone = timezoneForStop(true, null);
        String pickupDateTime = pickupDateTime(capturedOrder, capturedOrder == null ? null : capturedOrder.pickup, pickupZone);
        return ZonedDateTime.parse(pickupDateTime)
                .plusDays(scheduleConfig().getDefaultDropoffDaysAfterPickup())
                .withZoneSameInstant(zone)
                .format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
    }

    private String dateAt8AmPacific(String dateValue) {
        LocalDate date = extractLocalDate(normalizeDateInput(dateValue));
        if (date == null) {
            return null;
        }
        return ZonedDateTime.of(date, TIME_OF_DAY_8AM, PACIFIC_ZONE)
                .format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
    }

    private LocalDate extractLocalDate(String cleaned) {
        if (isBlank(cleaned)) {
            return null;
        }
        try {
            return ZonedDateTime.parse(cleaned).toLocalDate();
        } catch (DateTimeParseException ignored) {}
        LocalDateTime ldt = parseLocalDateTime(cleaned);
        if (ldt != null) {
            return ldt.toLocalDate();
        }
        LocalDate ld = parseLocalDate(cleaned);
        if (ld != null) {
            return ld;
        }
        Matcher matcher = SCRAPED_DATE_TIME.matcher(cleaned);
        if (matcher.matches()) {
            try {
                return LocalDate.parse(
                        Year.now().getValue() + " " + matcher.group(1) + " " + matcher.group(2),
                        DateTimeFormatter.ofPattern("yyyy MMM d", Locale.US)
                );
            } catch (DateTimeParseException ignored) {}
        }
        return null;
    }

    private String stopDateTimeValue(CapturedOrdersPayload.Stop stop, boolean pickup) {
        if (stop instanceof CapturedOrdersPayload.RouteStop routeStop) {
            if (pickup) {
                return firstNonBlank(routeStop.dateTime, routeStop.earliestPickupDateTime, routeStop.latestPickupDateTime);
            }
            return firstNonBlank(routeStop.dateTime, routeStop.earliestDropoffDateTime, routeStop.latestDropoffDateTime);
        }
        return stop == null ? null : stop.dateTime;
    }

    private String normalizeDateTime(String value, ZoneId defaultZone) {
        String cleaned = normalizeDateInput(value);
        if (isBlank(cleaned)) {
            return null;
        }

        if (isIsoZonedDateTime(cleaned)) {
            return cleaned;
        }

        String normalized = cleaned.replace('\u2022', ' ').replaceAll("\\s+", " ").trim();

        Matcher matcher = SCRAPED_DATE_TIME.matcher(cleaned);
        if (!matcher.matches()) {
            LocalDateTime localDateTime = parseLocalDateTime(normalized);
            if (localDateTime != null) {
                return ZonedDateTime.of(localDateTime, defaultZone).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
            }

            LocalDate localDate = parseLocalDate(normalized);
            if (localDate != null) {
                return localDate.atStartOfDay(defaultZone).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
            }

            throw new IllegalArgumentException("Unsupported order date/time format: " + value);
        }

        int year = Year.now().getValue();
        String month = matcher.group(1);
        String day = matcher.group(2);
        String time = matcher.group(3);
        ZoneId zone = zoneForAbbreviation(matcher.group(4));

        LocalDateTime localDateTime = LocalDateTime.parse(
                year + " " + month + " " + day + " " + time,
                SCRAPED_DATE_TIME_FORMATTER
        );

        return ZonedDateTime.of(localDateTime, zone).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
    }

    private String normalizeDateInput(String value) {
        if (isBlank(value)) {
            return null;
        }

        return value
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace('\u2007', ' ')
                .replaceAll("[\\p{Z}\\s]+", " ")
                .trim();
    }

    private LocalDateTime parseLocalDateTime(String value) {
        for (DateTimeFormatter formatter : LOCAL_DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // try next formatter
            }
        }
        return null;
    }

    private LocalDate parseLocalDate(String value) {
        for (DateTimeFormatter formatter : LOCAL_DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // try next formatter
            }
        }
        return null;
    }

    private boolean isIsoZonedDateTime(String value) {
        try {
            ZonedDateTime.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private ZoneId zoneForAbbreviation(String abbreviation) {
        return switch (abbreviation.toUpperCase(Locale.US)) {
            case "EST", "EDT" -> ZoneId.of("America/New_York");
            case "CST", "CDT" -> ZoneId.of("America/Chicago");
            case "MST", "MDT" -> ZoneId.of("America/Denver");
            case "PST", "PDT" -> ZoneId.of("America/Los_Angeles");
            case "UTC" -> ZoneId.of("UTC");
            default -> throw new DateTimeException("Unsupported order timezone abbreviation: " + abbreviation);
        };
    }

    private BigDecimal parseDecimal(String value) {
        String normalized = normalizeDecimalValue(value);
        if (normalized == null) {
            return null;
        }

        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer parseInteger(String value) {
        BigDecimal decimal = parseDecimal(value);
        return decimal == null ? null : decimal.intValue();
    }

    private String normalizeDecimalValue(String value) {
        if (isBlank(value)) {
            return null;
        }

        String normalized = value.replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace('\u2007', ' ')
                .replaceAll("[\\p{Z}\\s]+", " ")
                .replaceAll("(?i)\\b(?:lbs?|pounds?|pallets?|cases?|case|cubes?|cube|qty|quantity)\\b", "")
                .replaceAll("[^0-9,.+\\-]", "")
                .trim();
        if (normalized.isBlank()
                || !normalized.matches("[-+]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?")) {
            return null;
        }
        return normalized.replace(",", "");
    }

    private String firstValidPoNumber(String... values) {
        if (values == null || values.length == 0) {
            return null;
        }

        for (String value : values) {
            String candidate = normalizePoNumber(value);
            if (candidate != null) {
                return candidate;
            }
        }

        return null;
    }

    private String joinedPoNumbers(CapturedOrdersPayload.CapturedOrder capturedOrder, String... additionalValues) {
        List<String> values = new ArrayList<>();
        if (capturedOrder != null && capturedOrder.poNumbers != null) {
            addPoNumbers(values, capturedOrder.poNumbers);
        }
        if (additionalValues != null && additionalValues.length > 0) {
            addPoNumbers(values, additionalValues);
        }
        return values.isEmpty() ? null : String.join(",", values);
    }

    private void addPoNumbers(List<String> values, List<String> candidates) {
        if (values == null || candidates == null || candidates.isEmpty()) {
            return;
        }
        for (String candidateValue : candidates) {
            String candidate = normalizePoNumber(candidateValue);
            if (candidate != null && !values.contains(candidate)) {
                values.add(candidate);
            }
        }
    }

    private void addPoNumbers(List<String> values, String... candidates) {
        if (values == null || candidates == null || candidates.length == 0) {
            return;
        }
        for (String candidateValue : candidates) {
            String candidate = normalizePoNumber(candidateValue);
            if (candidate != null && !values.contains(candidate)) {
                values.add(candidate);
            }
        }
    }

    private String normalizePoNumber(String value) {
        if (isBlank(value)) {
            return null;
        }

        String cleaned = value.replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace('\u2007', ' ')
                .replaceAll("[\\p{Z}\\s]+", " ")
                .trim();
        if (cleaned.isBlank() || looksLikeDateLikeValue(cleaned)) {
            return null;
        }
        return cleaned;
    }

    private boolean looksLikeDateLikeValue(String value) {
        if (isBlank(value)) {
            return false;
        }

        String cleaned = value.trim();
        return isIsoZonedDateTime(cleaned)
                || parseLocalDateTime(cleaned) != null
                || parseLocalDate(cleaned) != null
                || SCRAPED_DATE_TIME.matcher(cleaned).matches()
                || cleaned.matches("(?i)^(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+\\d{1,2}(?:,\\s*\\d{4})?(?:\\s+at\\s+.*)?$")
                || cleaned.matches("(?i)^\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:am|pm|a\\.m\\.|p\\.m\\.)?(?:\\s+[A-Z]{2,4})?$");
    }

    private void validateRequiredIds(List<CapturedOrdersPayload.CapturedOrder> orders) {
        requireId("agent.load.payment-addon-type-id", config.getPaymentAddonTypeId());
        requireId("agent.load.credit-recipient.user-id", config.getCreditRecipient() == null ? null : config.getCreditRecipient().getUserId());
        requireId("agent.load.credit-recipient.commission-plan-id", config.getCreditRecipient() == null ? null : config.getCreditRecipient().getCommissionPlanId());
        requireId("agent.load.shipper-id", config.getShipperId());
        requireId("agent.load.commodity-id", config.getCommodityId());
        requireId("agent.load.address.id.pickup", pickupAddressId());
        requireId("agent.load.address.id.dropoff", dropoffAddressId());
        requireId("agent.load.company.id", config.getCompany() == null ? null : config.getCompany().getId());
    }

    private String resolveCompanyId() {
        return config.getCompany() == null ? null : config.getCompany().getId();
    }

    private AgentLoadConfig.StopDefaults addressDefaults(boolean pickup) {
        if (config.getAddress() == null
                || config.getAddress().getDefaults() == null) {
            return new AgentLoadConfig.StopDefaults();
        }

        AgentLoadConfig.StopDefaults defaults = pickup
                ? config.getAddress().getDefaults().getPickup()
                : config.getAddress().getDefaults().getDropoff();
        return defaults == null ? new AgentLoadConfig.StopDefaults() : defaults;
    }

    private AgentLoadConfig.Schedule scheduleConfig() {
        return config.getSchedule() == null ? new AgentLoadConfig.Schedule() : config.getSchedule();
    }

    private AgentLoadConfig.SupplementData supplementDataConfig() {
        return config.getSupplementData() == null ? new AgentLoadConfig.SupplementData() : config.getSupplementData();
    }

    private String defaultSourceOrderId() {
        return firstNonBlank(supplementDataConfig().getDefaultSourceOrderId(), "UNKNOWN");
    }

    private ZoneId timezoneForStop(boolean pickup, String scrapedTimezone) {
        String timezone = firstNonBlank(
                addressDefaults(pickup).getTimezone(),
                scrapedTimezone,
                scheduleConfig().getDefaultTimezone(),
                DEFAULT_ZONE.getId()
        );
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException e) {
            return DEFAULT_ZONE;
        }
    }

    private String pickupAddressId() {
        if (config.getAddress() == null || config.getAddress().getId() == null) {
            return null;
        }
        return config.getAddress().getId().getPickup();
    }

    private String dropoffAddressId() {
        if (config.getAddress() == null || config.getAddress().getId() == null) {
            return null;
        }
        return config.getAddress().getId().getDropoff();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim());
    }

    private void requireId(String property, String value) {
        if (isBlank(value)) {
            throw new IllegalStateException("Missing required Load API mapping id: " + property);
        }
    }

    private record AddressParts(String streetAddress, String city, String state, String zip) {
    }
}
