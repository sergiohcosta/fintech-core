package com.fintech.api.service;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.dto.budget.BudgetCycleSummaryDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BudgetSummaryService {

    @Transactional(readOnly = true)
    public BudgetCycleSummaryDTO calculateSummary(BudgetCycle cycle, List<BudgetItem> items,
                                                  List<Transaction> unplanned, LocalDate today) {
        BigDecimal openingBalance = cycle.getOpeningBalance();

        List<BudgetItem> activeItems = items.stream()
            .filter(i -> i.getStatus() != BudgetItemStatus.SKIPPED)
            .toList();

        BigDecimal plannedIncome  = sumByType(activeItems, TransactionType.INCOME);
        BigDecimal plannedExpense = sumByType(activeItems, TransactionType.EXPENSE);

        BigDecimal realizedIncome  = sumByTypeAndStatus(activeItems, TransactionType.INCOME,  BudgetItemStatus.REALIZED);
        BigDecimal realizedExpense = sumByTypeAndStatus(activeItems, TransactionType.EXPENSE, BudgetItemStatus.REALIZED);

        BigDecimal projectedBalance = openingBalance.add(plannedIncome).subtract(plannedExpense);

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

        Integer remainingDays = cycle.getStatus() == BudgetCycleStatus.OPEN
            ? (int) ChronoUnit.DAYS.between(today, cycle.getEndDate())
            : null;
        BigDecimal dailyAllowance = cycle.getStatus() == BudgetCycleStatus.OPEN
            ? calculateDailyAllowance(availableToSpend, cycle.getEndDate(), today)
            : null;

        long pendingCount = activeItems.stream()
            .filter(i -> i.getStatus() == BudgetItemStatus.PENDING)
            .count();

        return new BudgetCycleSummaryDTO(
            openingBalance,
            plannedIncome,
            plannedExpense,
            projectedBalance,
            realizedIncome,
            realizedExpense,
            unplannedIncome,
            unplannedExpense,
            currentBalance,
            availableToSpend,
            dailyAllowance,
            remainingDays,
            pendingCount
        );
    }

    public BigDecimal calculateDailyAllowance(BigDecimal availableToSpend, LocalDate endDate, LocalDate today) {
        if (availableToSpend.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        long remainingDays = ChronoUnit.DAYS.between(today, endDate);
        if (remainingDays <= 0) return BigDecimal.ZERO;
        return availableToSpend.divide(BigDecimal.valueOf(remainingDays), 2, RoundingMode.FLOOR);
    }

    private BigDecimal sumByType(List<BudgetItem> items, TransactionType type) {
        return items.stream()
            .filter(i -> i.getType() == type)
            .map(BudgetItem::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumByTypeAndStatus(List<BudgetItem> items, TransactionType type, BudgetItemStatus status) {
        return items.stream()
            .filter(i -> i.getType() == type && i.getStatus() == status)
            .map(BudgetItem::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
