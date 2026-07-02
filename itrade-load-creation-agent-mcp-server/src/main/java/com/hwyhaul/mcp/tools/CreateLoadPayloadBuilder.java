package com.hwyhaul.mcp.tools;

import com.hwyhaul.mcp.browser.OrderRow;
import com.hwyhaul.mcp.model.CreateLoadPayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class CreateLoadPayloadBuilder {

    public static CreateLoadPayload build(List<OrderRow> orders) {
        CreateLoadPayload payload = new CreateLoadPayload();

        payload.loads = orders.stream().map(o -> {
            CreateLoadPayload.Load load = new CreateLoadPayload.Load();
            load.externalOrderId = o.orderNo;
            load.shipperId = o.shipperId;
            load.customerName = o.customerName;
            load.shipDate = o.shipDate;
            load.deliveryDate = o.deliveryDate;

            load.pickup = new CreateLoadPayload.Stop();
            load.pickup.addressId = o.pickupAddressId;
            String pickupLocation = normalizeStopValue(o.pickupLocation);
            String pickupStreetAddress = normalizeStopValue(o.pickupStreetAddress);
            String pickupPreferredStreetAddress = preferStreetAddress(pickupStreetAddress, pickupLocation);
            String pickupPreferredLocation = preferStreetAddress(pickupLocation, pickupPreferredStreetAddress);
            String pickupCityStateZip = joinCityStateZip(o.pickupCity, o.pickupState, o.pickupZip);
            load.pickup.location = firstNonBlank(pickupPreferredLocation, pickupCityStateZip);
            load.pickup.streetAddress = firstNonBlank(pickupPreferredStreetAddress, pickupPreferredLocation, pickupCityStateZip);
            load.pickup.city = o.pickupCity;
            load.pickup.state = o.pickupState;
            load.pickup.zip = o.pickupZip;
            load.pickup.dateTime = o.pickupDateTime;

            load.dropoff = new CreateLoadPayload.Stop();
            load.dropoff.addressId = o.dropoffAddressId;
            String dropoffLocation = normalizeStopValue(o.dropoffLocation);
            String dropoffStreetAddress = normalizeStopValue(o.dropoffStreetAddress);
            String dropoffPreferredStreetAddress = preferStreetAddress(dropoffStreetAddress, dropoffLocation);
            String dropoffPreferredLocation = preferStreetAddress(dropoffLocation, dropoffPreferredStreetAddress);
            String dropoffCityStateZip = joinCityStateZip(o.dropoffCity, o.dropoffState, o.dropoffZip);
            load.dropoff.location = firstNonBlank(dropoffPreferredLocation, dropoffCityStateZip);
            load.dropoff.streetAddress = firstNonBlank(dropoffPreferredStreetAddress, dropoffPreferredLocation, dropoffCityStateZip);
            load.dropoff.city = o.dropoffCity;
            load.dropoff.state = o.dropoffState;
            load.dropoff.zip = o.dropoffZip;
            load.dropoff.dateTime = o.dropoffDateTime;

            load.commodity = o.commodity;
            load.palletCount = o.palletCount;
            load.weight = o.weight;
            load.poNumbers = sanitizePoNumbers(o.poNumbers, o.routeStops);
            load.status = o.status;
            load.routeStops = buildRouteStops(o, load.pickup, load.dropoff);

            return load;
        }).collect(Collectors.toList());

        return payload;
    }

    private static List<CreateLoadPayload.RouteStop> buildRouteStops(
            OrderRow order,
            CreateLoadPayload.Stop pickup,
            CreateLoadPayload.Stop dropoff
    ) {
        if (order.routeStops != null && !order.routeStops.isEmpty()) {
            return order.routeStops.stream()
                    .filter(routeStop -> routeStop != null)
                    .sorted(Comparator.comparing(
                            routeStop -> routeStop.orderSequenceNumber,
                            Comparator.nullsLast(Integer::compareTo)))
                    .map(CreateLoadPayloadBuilder::copyRouteStop)
                    .collect(Collectors.toList());
        }

        List<CreateLoadPayload.RouteStop> routeStops = new ArrayList<>();
        if (pickup != null) {
            routeStops.add(copyRouteStop(pickup, 1, "PICK"));
        }
        if (dropoff != null) {
            routeStops.add(copyRouteStop(dropoff, 2, "DROP"));
        }
        return routeStops;
    }

    private static CreateLoadPayload.RouteStop copyRouteStop(OrderRow.RouteStop source) {
        if (source == null) {
            return null;
        }

        CreateLoadPayload.RouteStop target = new CreateLoadPayload.RouteStop();
        copyStopFields(target, source);
        target.orderSequenceNumber = source.orderSequenceNumber;
        target.sequenceType = source.sequenceType;
        target.pickUpNumber = source.pickUpNumber;
        target.dropOffNumber = source.dropOffNumber;
        target.poNumber = normalizePoNumber(source.poNumber);
        target.earliestPickupDateTime = source.earliestPickupDateTime;
        target.latestPickupDateTime = source.latestPickupDateTime;
        target.earliestDropoffDateTime = source.earliestDropoffDateTime;
        target.latestDropoffDateTime = source.latestDropoffDateTime;
        target.weight = source.weight;
        target.palletCount = source.palletCount;
        target.caseCount = source.caseCount;
        target.timezone = source.timezone;
        return target;
    }

    private static CreateLoadPayload.RouteStop copyRouteStop(CreateLoadPayload.Stop source, int sequenceNumber, String sequenceType) {
        if (source == null) {
            return null;
        }

        CreateLoadPayload.RouteStop target = new CreateLoadPayload.RouteStop();
        copyStopFields(target, source);
        target.orderSequenceNumber = sequenceNumber;
        target.sequenceType = sequenceType;
        return target;
    }

    private static void copyStopFields(CreateLoadPayload.Stop target, CreateLoadPayload.Stop source) {
        if (target == null || source == null) {
            return;
        }

        target.addressId = source.addressId;
        target.location = source.location;
        target.streetAddress = source.streetAddress;
        target.city = source.city;
        target.state = source.state;
        target.zip = source.zip;
        target.dateTime = source.dateTime;
    }

    private static void copyStopFields(CreateLoadPayload.Stop target, OrderRow.RouteStop source) {
        if (target == null || source == null) {
            return;
        }

        target.addressId = source.addressId;
        target.location = source.location;
        target.streetAddress = source.streetAddress;
        target.city = source.city;
        target.state = source.state;
        target.zip = source.zip;
        target.dateTime = source.dateTime;
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static String firstNonBlank(String first, String second, String third) {
        return first == null || first.isBlank() ? firstNonBlank(second, third) : first;
    }

    private static List<String> sanitizePoNumbers(List<String> poNumbers, List<OrderRow.RouteStop> routeStops) {
        List<String> cleaned = new ArrayList<>();
        if (poNumbers != null) {
            for (String poNumber : poNumbers) {
                String candidate = normalizePoNumber(poNumber);
                if (candidate != null && !cleaned.contains(candidate)) {
                    cleaned.add(candidate);
                }
            }
        }

        if (!cleaned.isEmpty()) {
            return cleaned;
        }

        if (routeStops == null || routeStops.isEmpty()) {
            return cleaned;
        }

        for (OrderRow.RouteStop routeStop : routeStops) {
            if (routeStop == null) {
                continue;
            }

            String candidate = normalizePoNumber(routeStop.poNumber);
            if (candidate != null && !cleaned.contains(candidate)) {
                cleaned.add(candidate);
            }
        }

        return cleaned;
    }

    private static String normalizePoNumber(String value) {
        if (value == null) {
            return null;
        }

        String cleaned = value.replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace('\u2007', ' ')
                .replaceAll("[\\p{Z}\\s]+", " ")
                .trim();
        if (cleaned.isBlank() || looksLikeDateValue(cleaned)) {
            return null;
        }
        return cleaned;
    }

    private static boolean looksLikeDateValue(String value) {
        if (value == null) {
            return false;
        }

        String trimmed = value.trim();
        return trimmed.matches("\\d{4}-\\d{2}-\\d{2}([T ].*)?")
                || trimmed.matches("\\d{1,2}/\\d{1,2}/\\d{4}([ T].*)?")
                || trimmed.matches("\\d{4}/\\d{1,2}/\\d{1,2}([ T].*)?")
                || trimmed.matches("[A-Za-z]{3,9}\\s+\\d{1,2},\\s+\\d{4}.*")
                || trimmed.matches("(?i)^(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+\\d{1,2}(?:,\\s*\\d{4})?(?:\\s+at\\s+.*)?$")
                || trimmed.matches("(?i)^\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:am|pm|a\\.m\\.|p\\.m\\.)?(?:\\s+[A-Z]{2,4})?$");
    }

    private static String normalizeStopValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace('\u2007', ' ')
                .replaceAll("[\\p{Z}\\s]+", " ")
                .trim()
                .toLowerCase()
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

    private static String preferStreetAddress(String current, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return current;
        }
        if (current == null || current.isBlank()) {
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

    private static String joinCityStateZip(String city, String state, String zip) {
        if ((city == null || city.isBlank()) && (state == null || state.isBlank()) && (zip == null || zip.isBlank())) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        if (city != null && !city.isBlank()) {
            builder.append(city.trim());
        }
        if (state != null && !state.isBlank()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(state.trim());
        }
        if (zip != null && !zip.isBlank()) {
            if (builder.length() > 0) {
                builder.append(" ");
            }
            builder.append(zip.trim());
        }
        return builder.toString();
    }

    private static boolean looksLikeAddress(String value) {
        if (value == null || value.isBlank()) {
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
}
