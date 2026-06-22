package com.fintech.api.dto.budget;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

public record BudgetCycleResponseDTO(
    UUID id,
    LocalDate startDate,
    LocalDate endDate,
    BigDecimal openingBalance,
    BudgetCycleStatus status,
    BudgetCycleSummaryDTO summary,
    List<BudgetItemResponseDTO> items,
    List<TransactionResponseDTO> unplannedTransactions
) {
    public static BudgetCycleResponseDTO fromEntity(
            BudgetCycle cycle,
            List<BudgetItem> items,
            List<Transaction> unplanned) {

        List<BudgetItemResponseDTO> itemDTOs = items.stream()
            .map(BudgetItemResponseDTO::fromEntity)
            .toList();
        List<TransactionResponseDTO> unplannedDTOs = unplanned.stream()
            .map(TransactionResponseDTO::fromEntity)
            .toList();

        return new BudgetCycleResponseDTO(
            cycle.getId(),
            cycle.getStartDate(),
            cycle.getEndDate(),
            cycle.getOpeningBalance(),
            cycle.getStatus(),
            buildSummary(cycle, items, unplanned),
            itemDTOs,
            unplannedDTOs
        );
    }

    private static BudgetCycleSummaryDTO buildSummary(
            BudgetCycle cycle,
            List<BudgetItem> items,
            List<Transaction> unplanned) {

        BigDecimal openingBalance  = cycle.getOpeningBalance();
        BigDecimal plannedIncome   = BigDecimal.ZERO;
        BigDecimal plannedExpense  = BigDecimal.ZERO;
        BigDecimal realizedIncome  = BigDecimal.ZERO;
        BigDecimal realizedExpense = BigDecimal.ZERO;
        long pendingCount = 0;

        for (BudgetItem item : items) {
            boolean isIncome = item.getType() == TransactionType.INCOME;
            if (isIncome) plannedIncome  = plannedIncome.add(item.getAmount());
            else          plannedExpense = plannedExpense.add(item.getAmount());

            if (item.getStatus() == BudgetItemStatus.REALIZED) {
                if (isIncome) realizedIncome  = realizedIncome.add(item.getAmount());
                else          realizedExpense = realizedExpense.add(item.getAmount());
            }
            if (item.getStatus() == BudgetItemStatus.PENDING) pendingCount++;
        }

        BigDecimal unplannedIncome  = BigDecimal.ZERO;
        BigDecimal unplannedExpense = BigDecimal.ZERO;
        for (Transaction t : unplanned) {
            if (t.getType() == TransactionType.INCOME)
                unplannedIncome  = unplannedIncome.add(t.getAmount());
            else
                unplannedExpense = unplannedExpense.add(t.getAmount());
        }

        BigDecimal currentBalance = openingBalance
            .add(realizedIncome).add(unplannedIncome)
            .subtract(realizedExpense).subtract(unplannedExpense);

        BigDecimal availableToSpend = currentBalance
            .subtract(plannedExpense.subtract(realizedExpense));

        Integer remainingDays = null;
        BigDecimal dailyAllowance = null;
        if (cycle.getStatus() == BudgetCycleStatus.OPEN) {
            LocalDate today = LocalDate.now();
            long days = ChronoUnit.DAYS.between(today, cycle.getEndDate());
            remainingDays = (int) days;
            dailyAllowance = availableToSpend.compareTo(BigDecimal.ZERO) > 0 && days > 0
                ? availableToSpend.divide(BigDecimal.valueOf(days), 2, RoundingMode.FLOOR)
                : BigDecimal.ZERO;
        }

        return new BudgetCycleSummaryDTO(
            plannedIncome,
            plannedExpense,
            openingBalance.add(plannedIncome).subtract(plannedExpense),
            realizedIncome,
            realizedExpense,
            currentBalance,
            pendingCount,
            unplannedIncome,
            unplannedExpense,
            availableToSpend,
            dailyAllowance,
            remainingDays
        );
    }
}
