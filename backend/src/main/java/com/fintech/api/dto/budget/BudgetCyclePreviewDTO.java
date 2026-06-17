package com.fintech.api.dto.budget;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BudgetCyclePreviewDTO(
    String referenceMonth,                      // repassar ao POST /open
    int startDay,                               // repassar ao POST /open
    LocalDate startDate,
    LocalDate endDate,
    BigDecimal suggestedOpeningBalance,
    List<RecurringItemPreviewDTO> recurringItems,
    List<InstallmentItemPreviewDTO> installmentItems,
    BigDecimal projectedIncome,
    BigDecimal projectedExpense
) {}
