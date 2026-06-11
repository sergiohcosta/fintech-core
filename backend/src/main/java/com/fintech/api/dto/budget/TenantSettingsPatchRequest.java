package com.fintech.api.dto.budget;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record TenantSettingsPatchRequest(
    @NotNull @Min(1) @Max(28) Integer budgetCycleStartDay
) {}
