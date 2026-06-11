package com.fintech.api.dto.budget;

import com.fintech.api.domain.enums.TransactionType;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record RecurringBudgetItemRequest(
    @NotBlank String description,
    @NotNull @DecimalMin("0.01") BigDecimal amount,
    @NotNull TransactionType type,
    @NotNull @Min(1) @Max(28) Integer dayOfMonth,
    UUID categoryId,
    UUID accountId
) {}
