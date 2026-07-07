package com.hwyhaul.agent.intake.mongo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PipelineRunStoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void extractsLoadIdFromFlatObject() {
        assertEquals("LOAD-1", PipelineRunStore.extractLoadId(mapper, "{\"id\":\"LOAD-1\"}"));
        assertEquals("LOAD-2", PipelineRunStore.extractLoadId(mapper, "{\"loadId\":\"LOAD-2\"}"));
    }

    @Test
    void extractsLoadIdFromDataWrapperAndArray() {
        assertEquals("LOAD-3", PipelineRunStore.extractLoadId(mapper, "{\"data\":{\"id\":\"LOAD-3\"}}"));
        assertEquals("LOAD-4", PipelineRunStore.extractLoadId(mapper, "{\"data\":[{\"id\":\"LOAD-4\"}]}"));
    }

    @Test
    void extractsLoadIdFromScalarDataValue() {
        assertEquals(
                "cf35d682-87ef-4299-8966-e70274cf5252",
                PipelineRunStore.extractLoadId(
                        mapper,
                        "{\"data\":\"cf35d682-87ef-4299-8966-e70274cf5252\",\"code\":\"OK\"}"));
    }

    @Test
    void summarizeFailReasonStripsVerboseTailsButKeepsStatusAndResponseBody() {
        String raw = "Load API call failed with 400 Bad Request after 1234 ms. URL: https://x/create"
                + ". Response body: {\"code\":\"BAD_REQUEST\",\"message\":\"Invalid shipper\"}"
                + ". Null or omitted payload fields: [orders[0].shipperId]"
                + ". Payload sent: {\"orders\":[{\"a\":1}]}"
                + ". Verify the resolved company, shipper, address, commodity, recipient, schedule, and supplement defaults.";

        assertEquals(
                "Load API call failed with 400 Bad Request after 1234 ms. URL: https://x/create"
                        + ". Response body: {\"code\":\"BAD_REQUEST\",\"message\":\"Invalid shipper\"}",
                PipelineRunStore.summarizeFailReason(raw));
    }

    @Test
    void summarizeFailReasonLeavesShortMessagesUntouched() {
        String message = "LOAD API POST failed before request. Missing HwyHaul x-hh-token after login capture.";
        assertEquals(message, PipelineRunStore.summarizeFailReason(message));
    }

    @Test
    void summarizeFailReasonCapsVeryLongMessages() {
        String message = "x".repeat(1000);
        String summary = PipelineRunStore.summarizeFailReason(message);
        assertEquals(501, summary.length());
        assertEquals('…', summary.charAt(summary.length() - 1));
    }

    @Test
    void returnsNullForNonJsonOrMissingId() {
        assertNull(PipelineRunStore.extractLoadId(mapper, "created"));
        assertNull(PipelineRunStore.extractLoadId(mapper, "LOAD API POST skipped. Configure load.api.create-url."));
        assertNull(PipelineRunStore.extractLoadId(mapper, "{\"status\":\"ok\"}"));
        assertNull(PipelineRunStore.extractLoadId(mapper, null));
        assertNull(PipelineRunStore.extractLoadId(mapper, ""));
    }
}
