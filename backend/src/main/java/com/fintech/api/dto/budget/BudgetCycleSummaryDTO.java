package com.fintech.api.dto.budget;

import java.math.BigDecimal;

public record BudgetCycleSummaryDTO(
    BigDecimal openingBalance,
    BigDecimal plannedIncome,
    BigDecimal plannedExpense,
    BigDecimal projectedBalance,
    BigDecimal realizedIncome,
    BigDecimal realizedExpense,
    BigDecimal unplannedIncome,
    BigDecimal unplannedExpense,
    BigDecimal currentBalance,
    BigDecimal availableToSpend,
    BigDecimal dailyAllowance,
    Integer remainingDays,
    long pendingCount
) {}
