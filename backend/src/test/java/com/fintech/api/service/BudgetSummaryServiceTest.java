package com.fintech.api.service;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.dto.budget.BudgetCycleSummaryDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BudgetSummaryServiceTest {

    @InjectMocks BudgetSummaryService summaryService;

    // ---- helpers ----

    private Tenant createTenant() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("Test Tenant");
        return tenant;
    }

    private BudgetCycle createCycle(Tenant tenant, BigDecimal openingBalance, LocalDate start, LocalDate end) {
        return BudgetCycle.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .startDate(start)
                .endDate(end)
                .openingBalance(openingBalance)
                .status(BudgetCycleStatus.OPEN)
                .build();
    }

    private BudgetItem createItem(TransactionType type, BudgetItemStatus status, BigDecimal amount) {
        return BudgetItem.builder()
                .id(UUID.randomUUID())
                .type(type)
                .status(status)
                .amount(amount)
                .description("item")
                .expectedDate(LocalDate.now())
                .build();
    }

    private Transaction mockTx(TransactionType type, BigDecimal amount) {
        Transaction tx = mock(Transaction.class);
        when(tx.getType()).thenReturn(type);
        when(tx.getAmount()).thenReturn(amount);
        return tx;
    }

    // ---- calculateSummary ----

    @Nested
    @DisplayName("calculateSummary")
    class CalculateSummaryTests {

        @Test
        @DisplayName("itens mistos (PENDING, REALIZED, SKIPPED) — calcula planejados e realizados corretamente")
        void calculateSummary_withMixedItems_calculatesCorrectly() {
            Tenant tenant = createTenant();
            LocalDate start = LocalDate.of(2026, 6, 1);
            LocalDate end = LocalDate.of(2026, 6, 30);
            BudgetCycle cycle = createCycle(tenant, new BigDecimal("1000"), start, end);

            List<BudgetItem> items = List.of(
                    createItem(TransactionType.INCOME, BudgetItemStatus.PENDING, new BigDecimal("5000")),
                    createItem(TransactionType.EXPENSE, BudgetItemStatus.PENDING, new BigDecimal("2000")),
                    createItem(TransactionType.EXPENSE, BudgetItemStatus.REALIZED, new BigDecimal("500")),
                    createItem(TransactionType.INCOME, BudgetItemStatus.SKIPPED, new BigDecimal("300"))
            );

            // Transação não planejada de despesa
            List<Transaction> unplanned = List.of(mockTx(TransactionType.EXPENSE, new BigDecimal("200")));

            LocalDate today = LocalDate.of(2026, 6, 10);
            BudgetCycleSummaryDTO summary = summaryService.calculateSummary(cycle, items, unplanned, today);

            // plannedIncome = 5000 (SKIPPED excluído)
            assertThat(summary.plannedIncome()).isEqualByComparingTo("5000");
            // plannedExpense = 2000 + 500 = 2500
            assertThat(summary.plannedExpense()).isEqualByComparingTo("2500");
            // projectedBalance = 1000 + 5000 - 2500 = 3500
            assertThat(summary.projectedBalance()).isEqualByComparingTo("3500");
            // realizedIncome = 0, realizedExpense = 500
            assertThat(summary.realizedIncome()).isEqualByComparingTo("0");
            assertThat(summary.realizedExpense()).isEqualByComparingTo("500");
            // unplannedExpense = 200 (da lista), unplannedIncome = 0
            assertThat(summary.unplannedExpense()).isEqualByComparingTo("200");
            assertThat(summary.unplannedIncome()).isEqualByComparingTo("0");
            // currentBalance = 1000 + 0 + 0 - 500 - 200 = 300
            assertThat(summary.currentBalance()).isEqualByComparingTo("300");
            // availableToSpend = currentBalance - (plannedExpense - realizedExpense) = 300 - 2000 = -1700
            assertThat(summary.availableToSpend()).isEqualByComparingTo("-1700");
            // pendingCount = 2 (INCOME/PENDING + EXPENSE/PENDING)
            assertThat(summary.pendingCount()).isEqualTo(2);
            // dailyAllowance = 0 (availableToSpend < 0)
            assertThat(summary.dailyAllowance()).isEqualByComparingTo("0");
            // remainingDays = 20 (10/jun → 30/jun)
            assertThat(summary.remainingDays()).isEqualTo(20);
        }

        @Test
        @DisplayName("todos SKIPPED — somatórios de itens zerados, currentBalance = openingBalance")
        void calculateSummary_skippedItemsExcluded() {
            Tenant tenant = createTenant();
            LocalDate start = LocalDate.of(2026, 6, 1);
            LocalDate end = LocalDate.of(2026, 6, 30);
            BudgetCycle cycle = createCycle(tenant, new BigDecimal("1000"), start, end);

            List<BudgetItem> items = List.of(
                    createItem(TransactionType.INCOME, BudgetItemStatus.SKIPPED, new BigDecimal("5000")),
                    createItem(TransactionType.EXPENSE, BudgetItemStatus.SKIPPED, new BigDecimal("2000")),
                    createItem(TransactionType.EXPENSE, BudgetItemStatus.SKIPPED, new BigDecimal("300"))
            );

            LocalDate today = LocalDate.of(2026, 6, 10);
            BudgetCycleSummaryDTO summary = summaryService.calculateSummary(cycle, items, List.of(), today);

            assertThat(summary.plannedIncome()).isEqualByComparingTo("0");
            assertThat(summary.plannedExpense()).isEqualByComparingTo("0");
            assertThat(summary.projectedBalance()).isEqualByComparingTo("1000");
            assertThat(summary.realizedIncome()).isEqualByComparingTo("0");
            assertThat(summary.realizedExpense()).isEqualByComparingTo("0");
            assertThat(summary.unplannedExpense()).isEqualByComparingTo("0");
            assertThat(summary.unplannedIncome()).isEqualByComparingTo("0");
            assertThat(summary.currentBalance()).isEqualByComparingTo("1000");
            assertThat(summary.availableToSpend()).isEqualByComparingTo("1000");
            assertThat(summary.pendingCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("receita não planejada aumenta currentBalance e availableToSpend")
        void calculateSummary_unplannedIncome_aumentaBalance() {
            Tenant tenant = createTenant();
            LocalDate start = LocalDate.of(2026, 6, 1);
            LocalDate end = LocalDate.of(2026, 6, 30);
            BudgetCycle cycle = createCycle(tenant, new BigDecimal("0"), start, end);

            List<Transaction> unplanned = List.of(
                    mockTx(TransactionType.INCOME,  new BigDecimal("500")),
                    mockTx(TransactionType.EXPENSE, new BigDecimal("100"))
            );

            BudgetCycleSummaryDTO summary = summaryService.calculateSummary(cycle, List.of(), unplanned, LocalDate.of(2026, 6, 1));

            assertThat(summary.unplannedIncome()).isEqualByComparingTo("500");
            assertThat(summary.unplannedExpense()).isEqualByComparingTo("100");
            // currentBalance = 0 + 500 - 100 = 400
            assertThat(summary.currentBalance()).isEqualByComparingTo("400");
            // availableToSpend = 400 - 0 = 400
            assertThat(summary.availableToSpend()).isEqualByComparingTo("400");
        }
    }

    // ---- calculateDailyAllowance ----

    @Nested
    @DisplayName("calculateDailyAllowance")
    class CalculateDailyAllowanceTests {

        @Test
        @DisplayName("positivo — calcula floor corretamente (divisão exata)")
        void calculateDailyAllowance_positiveAvailable_calculatesFloor() {
            BigDecimal available = new BigDecimal("1000");
            LocalDate today = LocalDate.of(2026, 6, 10);
            LocalDate endDate = LocalDate.of(2026, 6, 20);

            BigDecimal result = summaryService.calculateDailyAllowance(available, endDate, today);

            assertThat(result).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("positivo — floor com duas casas decimais (1000/7 = 142.85)")
        void calculateDailyAllowance_positiveAvailable_floorsToTwoDecimals() {
            BigDecimal available = new BigDecimal("1000");
            LocalDate today = LocalDate.of(2026, 6, 13);
            LocalDate endDate = LocalDate.of(2026, 6, 20);

            BigDecimal result = summaryService.calculateDailyAllowance(available, endDate, today);

            assertThat(result).isEqualByComparingTo("142.85");
        }

        @Test
        @DisplayName("disponível negativo — retorna ZERO")
        void calculateDailyAllowance_negativeAvailable_returnsZero() {
            BigDecimal available = new BigDecimal("-500");
            LocalDate today = LocalDate.of(2026, 6, 10);
            LocalDate endDate = LocalDate.of(2026, 6, 20);

            BigDecimal result = summaryService.calculateDailyAllowance(available, endDate, today);

            assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("zero dias restantes (today == endDate) — retorna ZERO")
        void calculateDailyAllowance_zeroRemainingDays_returnsZero() {
            BigDecimal available = new BigDecimal("1000");
            LocalDate today = LocalDate.of(2026, 6, 20);
            LocalDate endDate = LocalDate.of(2026, 6, 20);

            BigDecimal result = summaryService.calculateDailyAllowance(available, endDate, today);

            assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("data expirada (today > endDate) — retorna ZERO")
        void calculateDailyAllowance_pastEndDate_returnsZero() {
            BigDecimal available = new BigDecimal("1000");
            LocalDate today = LocalDate.of(2026, 6, 25);
            LocalDate endDate = LocalDate.of(2026, 6, 20);

            BigDecimal result = summaryService.calculateDailyAllowance(available, endDate, today);

            assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ---- remainingDays e dailyAllowance — nulos fora de OPEN ----

    @Nested
    @DisplayName("remainingDays e dailyAllowance — nulos fora de OPEN")
    class NullFieldsForNonOpenTests {

        @Test
        @DisplayName("ciclo ENDED → remainingDays null e dailyAllowance null")
        void calculateSummary_ended_remainingDaysNull() {
            Tenant tenant = createTenant();
            LocalDate start = LocalDate.of(2026, 5, 1);
            LocalDate end = LocalDate.of(2026, 5, 31);
            BudgetCycle cycle = BudgetCycle.builder()
                    .id(UUID.randomUUID()).tenant(tenant)
                    .startDate(start).endDate(end)
                    .openingBalance(BigDecimal.ZERO)
                    .status(BudgetCycleStatus.ENDED)
                    .build();

            BudgetCycleSummaryDTO summary = summaryService.calculateSummary(cycle, List.of(), List.of(), LocalDate.of(2026, 6, 10));

            assertThat(summary.remainingDays()).isNull();
            assertThat(summary.dailyAllowance()).isNull();
        }

        @Test
        @DisplayName("ciclo OPEN → remainingDays = dias entre hoje e endDate")
        void calculateSummary_open_remainingDaysCorreto() {
            Tenant tenant = createTenant();
            LocalDate start = LocalDate.of(2026, 6, 1);
            LocalDate end = LocalDate.of(2026, 6, 30);
            LocalDate today = LocalDate.of(2026, 6, 10);
            BudgetCycle cycle = createCycle(tenant, BigDecimal.ZERO, start, end);

            BudgetCycleSummaryDTO summary = summaryService.calculateSummary(cycle, List.of(), List.of(), today);

            assertThat(summary.remainingDays()).isEqualTo(20);
        }
    }
}
