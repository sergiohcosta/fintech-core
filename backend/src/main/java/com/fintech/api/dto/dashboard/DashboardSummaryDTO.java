package com.fintech.api.dto.dashboard;

import java.math.BigDecimal;
import java.time.YearMonth;

public record DashboardSummaryDTO(
        YearMonth period,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal balance
) {
    public static DashboardSummaryDTO of(YearMonth period, BigDecimal income, BigDecimal expense) {
        return new DashboardSummaryDTO(period, income, expense, income.subtract(expense));
    }
}
