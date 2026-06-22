package com.fintech.api.service;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.enums.TransactionStatus;
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

        // Planejado = todos os itens ativos (qualquer status). Alimenta projeção e availableToSpend.
        BigDecimal plannedIncome  = sumByType(activeItems, TransactionType.INCOME);
        BigDecimal plannedExpense = sumByType(activeItems, TransactionType.EXPENSE);

        // Realizado (Modelo A) = item REALIZED cuja transação de fato moveu caixa (PAID).
        // Um item realizado ligado a transação PENDING NÃO conta como caixa — só currentBalance é caixa real.
        BigDecimal realizedIncome  = sumRealizedPaid(activeItems, TransactionType.INCOME);
        BigDecimal realizedExpense = sumRealizedPaid(activeItems, TransactionType.EXPENSE);

        BigDecimal projectedBalance = openingBalance.add(plannedIncome).subtract(plannedExpense);

        // Avulsas: PAID alimenta o caixa (currentBalance); o total (PAID+PENDING) alimenta availableToSpend.
        BigDecimal unplannedIncomePaid  = BigDecimal.ZERO;
        BigDecimal unplannedExpensePaid = BigDecimal.ZERO;
        BigDecimal unplannedIncomeTotal  = BigDecimal.ZERO;
        BigDecimal unplannedExpenseTotal = BigDecimal.ZERO;
        for (Transaction t : unplanned) {
            boolean income = t.getType() == TransactionType.INCOME;
            boolean paid   = t.getStatus() == TransactionStatus.PAID;
            if (income) {
                unplannedIncomeTotal = unplannedIncomeTotal.add(t.getAmount());
                if (paid) unplannedIncomePaid = unplannedIncomePaid.add(t.getAmount());
            } else {
                unplannedExpenseTotal = unplannedExpenseTotal.add(t.getAmount());
                if (paid) unplannedExpensePaid = unplannedExpensePaid.add(t.getAmount());
            }
        }

        // currentBalance = caixa real AGORA = só o que moveu (PAID).
        BigDecimal currentBalance = openingBalance
            .add(realizedIncome).add(unplannedIncomePaid)
            .subtract(realizedExpense).subtract(unplannedExpensePaid);

        // availableToSpend = projeção considerando TUDO que está lançado (exceto SKIPPED),
        // independente de pago/pendente: receita só ajuda quando lançada, despesa pesa assim que existe.
        // Equivale a: opening + toda receita − toda despesa.
        BigDecimal availableToSpend = projectedBalance
            .add(unplannedIncomeTotal).subtract(unplannedExpenseTotal);

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
            plannedIncome,
            plannedExpense,
            projectedBalance,
            realizedIncome,
            realizedExpense,
            currentBalance,
            pendingCount,
            unplannedIncomeTotal,
            unplannedExpenseTotal,
            availableToSpend,
            dailyAllowance,
            remainingDays
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

    // Realizado-caixa: item REALIZED cuja transação vinculada está PAID.
    // tx null (não deveria ocorrer em REALIZED) é tratado como não-realizado por segurança.
    private BigDecimal sumRealizedPaid(List<BudgetItem> items, TransactionType type) {
        return items.stream()
            .filter(i -> i.getType() == type
                && i.getStatus() == BudgetItemStatus.REALIZED
                && i.getTransaction() != null
                && i.getTransaction().getStatus() == TransactionStatus.PAID)
            .map(BudgetItem::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
