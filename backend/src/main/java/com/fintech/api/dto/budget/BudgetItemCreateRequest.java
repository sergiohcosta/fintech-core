package com.fintech.api.dto.budget;

import com.fintech.api.domain.enums.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BudgetItemCreateRequest(
    @NotBlank String description,
    @NotNull @DecimalMin("0.01") BigDecimal amount,
    @NotNull TransactionType type,
    @NotNull LocalDate expectedDate,
    UUID categoryId,
    UUID accountId
) {}
