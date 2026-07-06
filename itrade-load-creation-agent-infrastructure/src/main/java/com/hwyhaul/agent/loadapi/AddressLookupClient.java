package com.hwyhaul.agent.loadapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AddressLookupClient {

    private static final Logger log = LoggerFactory.getLogger(AddressLookupClient.class);

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;

    public AddressLookupClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper mapper,
            @Value("${services.address.lookup.enabled:true}") boolean enabled,
            @Value("${services.address.lookup.base-url:}") String baseUrl,
            @Value("${load.api.x-api-key:4206d6c3-16bf-444b-9e5f-36775f91c29c}") String apiKey,
            @Value("${load.api.connect-timeout-ms:10000}") long connectTimeoutMs,
            @Value("${load.api.read-timeout-ms:60000}") long readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.mapper = mapper;
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    /**
     * Resolves the HwyHaul address id for the given address by calling
     * {@code /services/companies/{companyId}/address/lookup}. Returns the id from
     * the response {@code data} field when present, otherwise {@link Optional#empty()}
     * so the caller can fall back to the configured default address id.
     */
    public Optional<String> lookupAddressId(
            String companyId,
            String hwyHaulToken,
            String streetAddress,
            String city,
            String state,
            String zip
    ) {
        if (!enabled) {
            log.debug("Address lookup skipped for '{}': services.address.lookup.enabled=false.", streetAddress);
            return Optional.empty();
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return Optional.empty();
        }
        if (isBlank(companyId) || isBlank(streetAddress)) {
            return Optional.empty();
        }
        if (isBlank(hwyHaulToken)) {
            log.debug("Address lookup skipped for '{}': missing HwyHaul x-hh-token.", streetAddress);
            return Optional.empty();
        }
        if (isBlank(apiKey)) {
            log.debug("Address lookup skipped for '{}': missing load.api.x-api-key.", streetAddress);
            return Optional.empty();
        }

        String url = baseUrl + "/services/companies/" + companyId + "/address/lookup";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "");
        data.put("addressLine1", "");
        data.put("addressLine2", streetAddress);
        data.put("phone", "");
        data.put("phoneCountryCode", "US");
        data.put("city", city != null ? city : "");
        data.put("state", state != null ? state : "");
        data.put("zip", zip != null ? zip : "");
        data.put("country", "US");
        data.put("overrideValidation", false);
        data.put("overrideAddressAlreadyExists", false);
        data.put("id", null);
        data.put("workingHours", List.of());
        data.put("operationType", null);
        data.put("note", null);
        data.put("type", "FACILITY");

        try {
            String response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("x-hh-token", hwyHaulToken)
                    .header("x-api-key", apiKey)
                    .body(Map.of("data", data))
                    .retrieve()
                    .body(String.class);

            if (response == null || response.isBlank()) {
                return Optional.empty();
            }

            JsonNode root = mapper.readTree(response);
            String id = extractId(root);
            if (id != null && !id.isBlank()) {
                log.debug("Address lookup resolved id={} for {} {} {} {}", id, streetAddress, city, state, zip);
                return Optional.of(id);
            }
            log.debug("Address lookup returned no id for {} {} {} {}", streetAddress, city, state, zip);
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("Address lookup call failed for {}: {}", streetAddress, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Address lookup response parse failed for {}: {}", streetAddress, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Extracts the address id from the lookup response. The API returns the id
     * directly as a string in {@code data} (e.g. {@code "data": "3f8c1e2a-..."});
     * an object {@code data} with an {@code id}, or a top-level {@code id}, are
     * also tolerated for robustness.
     */
    private String extractId(JsonNode root) {
        JsonNode dataNode = root.path("data");
        if (dataNode.isTextual()) {
            return blankToNull(dataNode.asText());
        }
        if (dataNode.isObject()) {
            String id = blankToNull(dataNode.path("id").asText(null));
            if (id != null) {
                return id;
            }
        }
        return blankToNull(root.path("id").asText(null));
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) ? null : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim());
    }
}
