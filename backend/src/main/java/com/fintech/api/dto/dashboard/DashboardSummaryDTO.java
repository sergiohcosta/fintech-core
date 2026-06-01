package com.fintech.api.dto.dashboard;

import java.math.BigDecimal;
import java.time.YearMonth;

public record DashboardSummaryDTO(
        YearMonth period,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal balance,
        long transactionCount,
        BigDecimal totalAccountBalance
) {
    public static DashboardSummaryDTO of(
            YearMonth period,
            BigDecimal income,
            BigDecimal expense,
            long transactionCount,
            BigDecimal totalAccountBalance) {
        return new DashboardSummaryDTO(
                period, income, expense, income.subtract(expense),
                transactionCount, totalAccountBalance);
    }
}
