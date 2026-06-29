package com.fintech.api.dto.recurrence;

import com.fintech.api.domain.enums.TransactionType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RecurrenceRuleCreateDTO(
        @NotBlank @Size(max = 255) String description,
        @NotNull @DecimalMin("0.01") BigDecimal baseAmount,
        @NotNull TransactionType type,
        UUID categoryId,
        @NotNull UUID accountId,
        @NotBlank @ValidRrule String rrule,
        @NotNull LocalDate startDate) {
}
