package com.fintech.api.dto.budget;

import jakarta.validation.constraints.*;

public record BudgetCycleOpenRequest(
    @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}", message = "Formato esperado: yyyy-MM")
    String referenceMonth,

    @NotNull @Min(1) @Max(28)
    Integer startDay
) {}
