package com.fintech.api.dto.budget;

import java.util.List;

public record BudgetCyclePageResponse(
    List<BudgetCycleResponseDTO> content,
    long totalElements,
    int totalPages,
    int number
) {}
