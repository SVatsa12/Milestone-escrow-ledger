package com.svatsa.escrow.outbox.events;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ProjectFunded(
    @JsonProperty("project_id") Long projectId,
    @JsonProperty("total_amount_paise") Long totalAmountPaise
) {
}
