package com.hwyhaul.agent.loadapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class AddressLookupClient {

    private static final Logger log = LoggerFactory.getLogger(AddressLookupClient.class);

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String apiKey;

    public AddressLookupClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper mapper,
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
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public Optional<String> lookupAddressId(String companyId, String addressLine1, String city, String state, String zip) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Optional.empty();
        }
        if (companyId == null || companyId.isBlank() || addressLine1 == null || addressLine1.isBlank()) {
            return Optional.empty();
        }

        String url = baseUrl + "/services/companies/" + companyId + "/address/lookup";
        Map<String, String> data = new LinkedHashMap<>();
        data.put("addressLine1", addressLine1);
        data.put("city", city != null ? city : "");
        data.put("state", state != null ? state : "");
        data.put("zip", zip != null ? zip : "");

        try {
            String response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
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
                log.debug("Address lookup resolved id={} for {} {} {} {}", id, addressLine1, city, state, zip);
                return Optional.of(id);
            }
            log.debug("Address lookup returned no id for {} {} {} {}", addressLine1, city, state, zip);
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("Address lookup call failed for {}: {}", addressLine1, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Address lookup response parse failed for {}: {}", addressLine1, e.getMessage());
            return Optional.empty();
        }
    }

    private String extractId(JsonNode root) {
        JsonNode dataNode = root.path("data");
        if (dataNode.isObject()) {
            String id = dataNode.path("id").asText(null);
            if (id != null && !id.isBlank() && !"null".equalsIgnoreCase(id)) {
                return id;
            }
        }
        String id = root.path("id").asText(null);
        return (id == null || "null".equalsIgnoreCase(id) || id.isBlank()) ? null : id;
    }
}
