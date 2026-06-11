package com.fintech.api.dto.budget;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.enums.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record BudgetCycleResponseDTO(
    UUID id,
    LocalDate startDate,
    LocalDate endDate,
    BigDecimal openingBalance,
    BudgetCycleStatus status,
    BudgetCycleSummaryDTO summary,
    List<BudgetItemResponseDTO> items
) {
    public static BudgetCycleResponseDTO fromEntity(BudgetCycle cycle, List<BudgetItem> items) {
        List<BudgetItemResponseDTO> itemDTOs = items.stream()
            .map(BudgetItemResponseDTO::fromEntity)
            .toList();
        return new BudgetCycleResponseDTO(
            cycle.getId(),
            cycle.getStartDate(),
            cycle.getEndDate(),
            cycle.getOpeningBalance(),
            cycle.getStatus(),
            buildSummary(items, cycle.getOpeningBalance()),
            itemDTOs
        );
    }

    private static BudgetCycleSummaryDTO buildSummary(List<BudgetItem> items, BigDecimal openingBalance) {
        BigDecimal plannedIncome   = BigDecimal.ZERO;
        BigDecimal plannedExpense  = BigDecimal.ZERO;
        BigDecimal realizedIncome  = BigDecimal.ZERO;
        BigDecimal realizedExpense = BigDecimal.ZERO;
        long pendingCount = 0;

        for (BudgetItem item : items) {
            boolean isIncome = item.getType() == TransactionType.INCOME;
            if (isIncome) plannedIncome   = plannedIncome.add(item.getAmount());
            else          plannedExpense  = plannedExpense.add(item.getAmount());

            if (item.getStatus() == BudgetItemStatus.REALIZED) {
                if (isIncome) realizedIncome  = realizedIncome.add(item.getAmount());
                else          realizedExpense = realizedExpense.add(item.getAmount());
            }
            if (item.getStatus() == BudgetItemStatus.PENDING) pendingCount++;
        }

        return new BudgetCycleSummaryDTO(
            plannedIncome,
            plannedExpense,
            openingBalance.add(plannedIncome).subtract(plannedExpense),
            realizedIncome,
            realizedExpense,
            openingBalance.add(realizedIncome).subtract(realizedExpense),
            pendingCount
        );
    }
}
