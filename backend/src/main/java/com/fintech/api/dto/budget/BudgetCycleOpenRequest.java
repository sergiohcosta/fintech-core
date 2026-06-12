package com.fintech.api.dto.budget;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record BudgetCycleOpenRequest(
    @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}", message = "Formato esperado: yyyy-MM")
    String referenceMonth
) {}
