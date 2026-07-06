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
    void returnsNullForNonJsonOrMissingId() {
        assertNull(PipelineRunStore.extractLoadId(mapper, "created"));
        assertNull(PipelineRunStore.extractLoadId(mapper, "LOAD API POST skipped. Configure load.api.create-url."));
        assertNull(PipelineRunStore.extractLoadId(mapper, "{\"status\":\"ok\"}"));
        assertNull(PipelineRunStore.extractLoadId(mapper, null));
        assertNull(PipelineRunStore.extractLoadId(mapper, ""));
    }
}
