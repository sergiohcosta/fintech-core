package com.fintech.api.dto.recurrence;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record RecurrenceRulePatchDTO(
        @Size(max = 255) String description,
        @DecimalMin("0.01") BigDecimal baseAmount) {
}
