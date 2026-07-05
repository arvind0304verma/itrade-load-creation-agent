package com.hwyhaul.agent.loadapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.Optional;

/**
 * Looks up whether a load has already been created in HwyHaul for a given
 * customer load number and company, using the
 * {@code /services/loads/lookup-by-customer-load-number} endpoint. Used to
 * avoid creating duplicate loads.
 */
@Service
public class LoadLookupClient {

    private static final Logger log = LoggerFactory.getLogger(LoadLookupClient.class);

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String lookupUrl;
    private final String apiKey;

    public LoadLookupClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper mapper,
            @Value("${load.api.lookup-url:https://qa.sentinel.hwyhaul.com/core/hwyhaul/services/loads/lookup-by-customer-load-number}") String lookupUrl,
            @Value("${load.api.x-api-key:4206d6c3-16bf-444b-9e5f-36775f91c29c}") String apiKey,
            @Value("${load.api.connect-timeout-ms:10000}") long connectTimeoutMs,
            @Value("${load.api.read-timeout-ms:60000}") long readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.mapper = mapper;
        this.lookupUrl = lookupUrl;
        this.apiKey = apiKey;
    }

    /**
     * Returns an existing load identifier if HwyHaul already has a load for the
     * given customer load number and company, otherwise {@link Optional#empty()}.
     * A failed or inconclusive lookup returns empty so callers do not block load
     * creation on a transient lookup problem.
     */
    public Optional<String> findExistingLoad(String companyId, String customerLoadNumber, String hwyHaulToken) {
        if (lookupUrl == null || lookupUrl.isBlank()) {
            return Optional.empty();
        }
        if (isBlank(companyId) || isBlank(customerLoadNumber)) {
            return Optional.empty();
        }
        if (isBlank(hwyHaulToken)) {
            log.debug("Load lookup skipped for customerLoadNumber={}: missing HwyHaul x-hh-token.", customerLoadNumber);
            return Optional.empty();
        }
        if (isBlank(apiKey)) {
            log.debug("Load lookup skipped for customerLoadNumber={}: missing load.api.x-api-key.", customerLoadNumber);
            return Optional.empty();
        }

        String url = UriComponentsBuilder.fromUriString(lookupUrl)
                .queryParam("customerLoadNumber", customerLoadNumber)
                .queryParam("companyId", companyId)
                .encode()
                .toUriString();

        try {
            String response = restClient.get()
                    .uri(url)
                    .header("x-hh-token", hwyHaulToken)
                    .header("x-api-key", apiKey)
                    .retrieve()
                    .body(String.class);

            if (response == null || response.isBlank()) {
                return Optional.empty();
            }

            Optional<String> existingId = extractExistingLoadId(mapper.readTree(response));
            if (existingId.isPresent()) {
                log.info("Load already exists in HwyHaul for customerLoadNumber={}, companyId={}: {}",
                        customerLoadNumber, companyId, existingId.get());
            } else {
                log.debug("No existing HwyHaul load for customerLoadNumber={}, companyId={}.",
                        customerLoadNumber, companyId);
            }
            return existingId;
        } catch (RestClientResponseException e) {
            // 404 (and similar not-found responses) mean the load does not exist yet.
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            log.warn("Load lookup failed for customerLoadNumber={} with {} {}. Treating as not found.",
                    customerLoadNumber, e.getStatusCode().value(), e.getStatusText());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Load lookup call or parse failed for customerLoadNumber={}: {}. Treating as not found.",
                    customerLoadNumber, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Interprets the lookup response defensively across possible shapes: a bare
     * object with an id, a {@code data} wrapper, or an array of matches. Returns
     * the first load-identifying value found, or empty when nothing indicates an
     * existing load.
     */
    private Optional<String> extractExistingLoadId(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return Optional.empty();
        }

        JsonNode data = root.has("data") ? root.path("data") : root;
        if (data.isNull() || data.isMissingNode()) {
            return Optional.empty();
        }

        if (data.isArray()) {
            for (JsonNode item : data) {
                Optional<String> id = idFromNode(item);
                if (id.isPresent()) {
                    return id;
                }
            }
            return Optional.empty();
        }

        return idFromNode(data);
    }

    private Optional<String> idFromNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        for (String key : new String[]{"id", "loadId", "loadNumber", "loadNo", "referenceNumber"}) {
            String value = node.path(key).asText(null);
            if (value != null && !value.isBlank() && !"null".equalsIgnoreCase(value)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim());
    }
}
