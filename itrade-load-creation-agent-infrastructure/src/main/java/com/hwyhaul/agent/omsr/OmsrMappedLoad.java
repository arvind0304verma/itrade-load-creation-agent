package com.hwyhaul.agent.omsr;

import com.hwyhaul.agent.model.CreateLoadPayload;

public record OmsrMappedLoad(String externalOrderId, CreateLoadPayload payload) {
}
