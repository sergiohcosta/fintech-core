package com.fintech.api.service;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.dto.budget.BudgetCycleSummaryDTO;
import com.fintech.api.repository.TransactionRepository;
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

    private final TransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public BudgetCycleSummaryDTO calculateSummary(BudgetCycle cycle, List<BudgetItem> items, LocalDate today) {
        BigDecimal openingBalance = cycle.getOpeningBalance();

        // Filter out SKIPPED items from all calculations
        List<BudgetItem> activeItems = items.stream()
            .filter(i -> i.getStatus() != BudgetItemStatus.SKIPPED)
            .toList();

        BigDecimal plannedIncome = sumByType(activeItems, TransactionType.INCOME);
        BigDecimal plannedExpense = sumByType(activeItems, TransactionType.EXPENSE);

        BigDecimal realizedIncome = sumByTypeAndStatus(activeItems, TransactionType.INCOME, BudgetItemStatus.REALIZED);
        BigDecimal realizedExpense = sumByTypeAndStatus(activeItems, TransactionType.EXPENSE, BudgetItemStatus.REALIZED);

        // Projected Balance = openingBalance + sum(INCOME, PENDING|REALIZED) - sum(EXPENSE, PENDING|REALIZED)
        BigDecimal projectedBalance = openingBalance.add(plannedIncome).subtract(plannedExpense);

        // Unplanned Expenses = sum of EXPENSE transactions in cycle period with no budget item link
        BigDecimal unplannedExpenses = calculateUnplannedExpenses(cycle);

        // Available to Spend = sum(INCOME, PENDING|REALIZED) - sum(EXPENSE, PENDING|REALIZED) - unplannedExpenses
        BigDecimal availableToSpend = plannedIncome.subtract(plannedExpense).subtract(unplannedExpenses);

        int remainingDays = (int) ChronoUnit.DAYS.between(today, cycle.getEndDate());
        BigDecimal dailyAllowance = calculateDailyAllowance(availableToSpend, cycle.getEndDate(), today);

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
            unplannedExpenses,
            availableToSpend,
            dailyAllowance,
            remainingDays,
            pendingCount
        );
    }

    /**
     * Calcula a mesada diária (daily allowance).
     * Retorna ZERO se disponível ≤ 0 ou dias restantes ≤ 0.
     * Caso contrário: floor(disponível / diasRestantes, 2 casas decimais).
     */
    public BigDecimal calculateDailyAllowance(BigDecimal availableToSpend, LocalDate endDate, LocalDate today) {
        if (availableToSpend.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        long remainingDays = ChronoUnit.DAYS.between(today, endDate);
        if (remainingDays <= 0) {
            return BigDecimal.ZERO;
        }

        return availableToSpend.divide(BigDecimal.valueOf(remainingDays), 2, RoundingMode.FLOOR);
    }

    /**
     * Soma despesas não planejadas: transações EXPENSE no período do ciclo
     * que não estão vinculadas a nenhum budget item.
     */
    @Transactional(readOnly = true)
    public BigDecimal calculateUnplannedExpenses(BudgetCycle cycle) {
        return transactionRepository.sumUnplannedExpenses(
            cycle.getTenant().getId(),
            cycle.getStartDate(),
            cycle.getEndDate(),
            cycle.getId()
        );
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
