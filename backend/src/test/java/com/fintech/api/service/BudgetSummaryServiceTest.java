package com.fintech.api.service;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.enums.TransactionStatus;
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

    private Transaction mockTx(TransactionType type, BigDecimal amount, TransactionStatus status) {
        Transaction tx = mock(Transaction.class);
        when(tx.getType()).thenReturn(type);
        when(tx.getAmount()).thenReturn(amount);
        when(tx.getStatus()).thenReturn(status);
        return tx;
    }

    // Item REALIZED ligado a uma transação com o status informado (PAID = moveu caixa).
    private BudgetItem createRealizedItem(TransactionType type, BigDecimal amount, TransactionStatus txStatus) {
        Transaction tx = mock(Transaction.class);
        when(tx.getStatus()).thenReturn(txStatus);
        return BudgetItem.builder()
                .id(UUID.randomUUID())
                .type(type)
                .status(BudgetItemStatus.REALIZED)
                .amount(amount)
                .description("item")
                .expectedDate(LocalDate.now())
                .transaction(tx)
                .build();
    }

    // ---- calculateSummary ----

    @Nested
    @DisplayName("calculateSummary")
    class CalculateSummaryTests {

        @Test
        @DisplayName("Modelo A — exemplo de referência: opening 10.000, 4 avulsas → current 9.500 / disponível 9.000")
        void calculateSummary_referenceExample_modeloA() {
            Tenant tenant = createTenant();
            LocalDate start = LocalDate.of(2026, 6, 1);
            LocalDate end = LocalDate.of(2026, 6, 30);
            BudgetCycle cycle = createCycle(tenant, new BigDecimal("10000"), start, end);

            // Sem itens planejados; as 4 transações do exemplo entram como avulsas.
            List<Transaction> unplanned = List.of(
                    mockTx(TransactionType.EXPENSE, new BigDecimal("1000"), TransactionStatus.PAID),
                    mockTx(TransactionType.EXPENSE, new BigDecimal("1000"), TransactionStatus.PENDING),
                    mockTx(TransactionType.INCOME,  new BigDecimal("500"),  TransactionStatus.PAID),
                    mockTx(TransactionType.INCOME,  new BigDecimal("500"),  TransactionStatus.PENDING)
            );

            LocalDate today = LocalDate.of(2026, 6, 1); // 29 dias até 30/jun
            BudgetCycleSummaryDTO summary = summaryService.calculateSummary(cycle, List.of(), unplanned, today);

            assertThat(summary.projectedBalance()).isEqualByComparingTo("10000");
            // display das avulsas = total (PAID + PENDING)
            assertThat(summary.unplannedIncome()).isEqualByComparingTo("1000");
            assertThat(summary.unplannedExpense()).isEqualByComparingTo("2000");
            // currentBalance (caixa real) = 10000 + 500 PAID − 1000 PAID = 9500
            assertThat(summary.currentBalance()).isEqualByComparingTo("9500");
            // availableToSpend = 10000 + (todas receitas 1000) − (todas despesas 2000) = 9000
            assertThat(summary.availableToSpend()).isEqualByComparingTo("9000");
            // dailyAllowance = 9000 / 29 = 310.34 (FLOOR)
            assertThat(summary.dailyAllowance()).isEqualByComparingTo("310.34");
            assertThat(summary.remainingDays()).isEqualTo(29);
        }

        @Test
        @DisplayName("itens mistos — realizado conta só quando a transação está PAID")
        void calculateSummary_withMixedItems_calculatesCorrectly() {
            Tenant tenant = createTenant();
            LocalDate start = LocalDate.of(2026, 6, 1);
            LocalDate end = LocalDate.of(2026, 6, 30);
            BudgetCycle cycle = createCycle(tenant, new BigDecimal("1000"), start, end);

            List<BudgetItem> items = List.of(
                    createItem(TransactionType.INCOME, BudgetItemStatus.PENDING, new BigDecimal("5000")),
                    createItem(TransactionType.EXPENSE, BudgetItemStatus.PENDING, new BigDecimal("2000")),
                    createRealizedItem(TransactionType.EXPENSE, new BigDecimal("500"), TransactionStatus.PAID),
                    createItem(TransactionType.INCOME, BudgetItemStatus.SKIPPED, new BigDecimal("300"))
            );

            // Avulsa de despesa, paga.
            List<Transaction> unplanned = List.of(
                    mockTx(TransactionType.EXPENSE, new BigDecimal("200"), TransactionStatus.PAID));

            LocalDate today = LocalDate.of(2026, 6, 10);
            BudgetCycleSummaryDTO summary = summaryService.calculateSummary(cycle, items, unplanned, today);

            // plannedIncome = 5000 (SKIPPED excluído)
            assertThat(summary.plannedIncome()).isEqualByComparingTo("5000");
            // plannedExpense = 2000 + 500 = 2500
            assertThat(summary.plannedExpense()).isEqualByComparingTo("2500");
            // projectedBalance = 1000 + 5000 - 2500 = 3500
            assertThat(summary.projectedBalance()).isEqualByComparingTo("3500");
            // realizedExpense = 500 (REALIZED + tx PAID)
            assertThat(summary.realizedIncome()).isEqualByComparingTo("0");
            assertThat(summary.realizedExpense()).isEqualByComparingTo("500");
            assertThat(summary.unplannedExpense()).isEqualByComparingTo("200");
            assertThat(summary.unplannedIncome()).isEqualByComparingTo("0");
            // currentBalance = 1000 + 0 + 0 - 500 - 200 = 300
            assertThat(summary.currentBalance()).isEqualByComparingTo("300");
            // availableToSpend = projected 3500 + 0 − 200 = 3300
            assertThat(summary.availableToSpend()).isEqualByComparingTo("3300");
            // pendingCount = 2 (INCOME/PENDING + EXPENSE/PENDING)
            assertThat(summary.pendingCount()).isEqualTo(2);
            // dailyAllowance = 3300 / 20 = 165.00
            assertThat(summary.dailyAllowance()).isEqualByComparingTo("165.00");
            assertThat(summary.remainingDays()).isEqualTo(20);
        }

        @Test
        @DisplayName("item REALIZED ligado a tx PENDING — não move currentBalance, mas pesa no availableToSpend")
        void calculateSummary_realizedPendingTx_naoContaNoCaixa() {
            Tenant tenant = createTenant();
            LocalDate start = LocalDate.of(2026, 6, 1);
            LocalDate end = LocalDate.of(2026, 6, 30);
            BudgetCycle cycle = createCycle(tenant, BigDecimal.ZERO, start, end);

            List<BudgetItem> items = List.of(
                    createRealizedItem(TransactionType.EXPENSE, new BigDecimal("300"), TransactionStatus.PENDING));

            BudgetCycleSummaryDTO summary =
                    summaryService.calculateSummary(cycle, items, List.of(), LocalDate.of(2026, 6, 1));

            assertThat(summary.plannedExpense()).isEqualByComparingTo("300");
            assertThat(summary.realizedExpense()).isEqualByComparingTo("0"); // tx PENDING não conta
            assertThat(summary.currentBalance()).isEqualByComparingTo("0");  // caixa intacto
            assertThat(summary.availableToSpend()).isEqualByComparingTo("-300"); // já comprometido
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
        @DisplayName("avulsas PAID aumentam currentBalance e availableToSpend igualmente")
        void calculateSummary_unplannedPaid_aumentaBalance() {
            Tenant tenant = createTenant();
            LocalDate start = LocalDate.of(2026, 6, 1);
            LocalDate end = LocalDate.of(2026, 6, 30);
            BudgetCycle cycle = createCycle(tenant, new BigDecimal("0"), start, end);

            List<Transaction> unplanned = List.of(
                    mockTx(TransactionType.INCOME,  new BigDecimal("500"), TransactionStatus.PAID),
                    mockTx(TransactionType.EXPENSE, new BigDecimal("100"), TransactionStatus.PAID)
            );

            BudgetCycleSummaryDTO summary =
                    summaryService.calculateSummary(cycle, List.of(), unplanned, LocalDate.of(2026, 6, 1));

            assertThat(summary.unplannedIncome()).isEqualByComparingTo("500");
            assertThat(summary.unplannedExpense()).isEqualByComparingTo("100");
            // currentBalance = 0 + 500 - 100 = 400
            assertThat(summary.currentBalance()).isEqualByComparingTo("400");
            // availableToSpend = 0 + 500 - 100 = 400
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
