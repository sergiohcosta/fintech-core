package com.fintech.api.service;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.dto.budget.BudgetCycleSummaryDTO;
import com.fintech.api.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetSummaryServiceTest {

    @Mock TransactionRepository transactionRepository;

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

    // ---- calculateSummary ----

    @Nested
    @DisplayName("calculateSummary")
    class CalculateSummaryTests {

        @Test
        @DisplayName("itens mistos (PENDING, REALIZED, SKIPPED) — calcula corretamente")
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

            when(transactionRepository.sumUnplannedExpenses(
                    eq(tenant.getId()), eq(start), eq(end), eq(cycle.getId())
            )).thenReturn(new BigDecimal("200"));

            LocalDate today = LocalDate.of(2026, 6, 10);
            BudgetCycleSummaryDTO summary = summaryService.calculateSummary(cycle, items, today);

            // plannedIncome = 5000 (SKIPPED excluído)
            assertThat(summary.plannedIncome()).isEqualByComparingTo("5000");
            // plannedExpense = 2000 + 500 = 2500
            assertThat(summary.plannedExpense()).isEqualByComparingTo("2500");
            // projectedBalance = 1000 + 5000 - 2500 = 3500
            assertThat(summary.projectedBalance()).isEqualByComparingTo("3500");
            // realizedIncome = 0 (nenhum INCOME REALIZED)
            assertThat(summary.realizedIncome()).isEqualByComparingTo("0");
            // realizedExpense = 500
            assertThat(summary.realizedExpense()).isEqualByComparingTo("500");
            // unplannedExpenses = 200
            assertThat(summary.unplannedExpenses()).isEqualByComparingTo("200");
            // availableToSpend = 5000 - 2500 - 200 = 2300
            assertThat(summary.availableToSpend()).isEqualByComparingTo("2300");
            // pendingCount = 2 (somente itens PENDING: INCOME/PENDING + EXPENSE/PENDING)
            assertThat(summary.pendingCount()).isEqualTo(2);
            // dailyAllowance calculado com base nos dias restantes (30 - 10 = 20 dias)
            // floor(2300 / 20) = 115.00
            assertThat(summary.dailyAllowance()).isEqualByComparingTo("115.00");
            // remainingDays = days between 2026-06-10 e 2026-06-30 = 20
            assertThat(summary.remainingDays()).isEqualTo(20);
        }

        @Test
        @DisplayName("todos SKIPPED — somatórios zerados")
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

            when(transactionRepository.sumUnplannedExpenses(
                    eq(tenant.getId()), eq(start), eq(end), eq(cycle.getId())
            )).thenReturn(BigDecimal.ZERO);

            LocalDate today = LocalDate.of(2026, 6, 10);
            BudgetCycleSummaryDTO summary = summaryService.calculateSummary(cycle, items, today);

            assertThat(summary.plannedIncome()).isEqualByComparingTo("0");
            assertThat(summary.plannedExpense()).isEqualByComparingTo("0");
            assertThat(summary.projectedBalance()).isEqualByComparingTo("1000"); // apenas openingBalance
            assertThat(summary.realizedIncome()).isEqualByComparingTo("0");
            assertThat(summary.realizedExpense()).isEqualByComparingTo("0");
            assertThat(summary.unplannedExpenses()).isEqualByComparingTo("0");
            assertThat(summary.availableToSpend()).isEqualByComparingTo("0");
            assertThat(summary.pendingCount()).isEqualTo(0);
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
            // remainingDays = 10, expected = 1000/10 = 100.00

            BigDecimal result = summaryService.calculateDailyAllowance(available, endDate, today);

            assertThat(result).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("positivo — floor com duas casas decimais (1000/7 = 142.85)")
        void calculateDailyAllowance_positiveAvailable_floorsToTwoDecimals() {
            BigDecimal available = new BigDecimal("1000");
            LocalDate today = LocalDate.of(2026, 6, 13);
            LocalDate endDate = LocalDate.of(2026, 6, 20);
            // remainingDays = 7, expected = floor(1000/7) = 142.85

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

    // ---- calculateUnplannedExpenses ----

    @Nested
    @DisplayName("calculateUnplannedExpenses")
    class CalculateUnplannedExpensesTests {

        @Test
        @DisplayName("delega ao repositório e retorna valor correto")
        void calculateUnplannedExpenses_delegatesToRepository() {
            Tenant tenant = createTenant();
            LocalDate start = LocalDate.of(2026, 6, 1);
            LocalDate end = LocalDate.of(2026, 6, 30);
            BudgetCycle cycle = createCycle(tenant, BigDecimal.ZERO, start, end);

            when(transactionRepository.sumUnplannedExpenses(
                    eq(tenant.getId()), eq(start), eq(end), eq(cycle.getId())
            )).thenReturn(new BigDecimal("350.00"));

            BigDecimal result = summaryService.calculateUnplannedExpenses(cycle);

            assertThat(result).isEqualByComparingTo("350.00");
            verify(transactionRepository).sumUnplannedExpenses(
                    tenant.getId(), start, end, cycle.getId()
            );
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

            when(transactionRepository.sumUnplannedExpenses(any(), any(), any(), any()))
                    .thenReturn(BigDecimal.ZERO);

            BudgetCycleSummaryDTO summary = summaryService.calculateSummary(cycle, List.of(), LocalDate.of(2026, 6, 10));

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
            BudgetCycle cycle = createCycle(tenant, BigDecimal.ZERO, start, end); // status OPEN

            when(transactionRepository.sumUnplannedExpenses(any(), any(), any(), any()))
                    .thenReturn(BigDecimal.ZERO);

            BudgetCycleSummaryDTO summary = summaryService.calculateSummary(cycle, List.of(), today);

            assertThat(summary.remainingDays()).isEqualTo(20); // entre 10/jun e 30/jun
        }
    }
}
