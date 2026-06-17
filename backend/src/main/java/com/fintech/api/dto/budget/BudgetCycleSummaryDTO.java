package com.fintech.api.dto.budget;

import java.math.BigDecimal;

public record BudgetCycleSummaryDTO(
    BigDecimal openingBalance,
    BigDecimal plannedIncome,
    BigDecimal plannedExpense,
    BigDecimal projectedBalance,
    BigDecimal realizedIncome,
    BigDecimal realizedExpense,
    BigDecimal unplannedExpenses,
    BigDecimal availableToSpend,
    BigDecimal dailyAllowance,   // null quando status != OPEN
    Integer remainingDays,       // null quando status != OPEN (era int)
    long pendingCount
) {}
