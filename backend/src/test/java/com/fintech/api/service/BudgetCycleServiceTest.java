package com.fintech.api.service;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.BudgetCycleOpenRequest;
import com.fintech.api.dto.budget.BudgetCycleSummaryDTO;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.BudgetCycleRepository;
import com.fintech.api.repository.BudgetItemRepository;
import com.fintech.api.repository.RecurringBudgetItemRepository;
import com.fintech.api.repository.TenantRepository;
import com.fintech.api.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetCycleServiceTest {

    @Mock BudgetCycleRepository cycleRepository;
    @Mock BudgetItemRepository itemRepository;
    @Mock RecurringBudgetItemRepository recurringRepository;
    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock TenantRepository tenantRepository;
    @Mock BudgetSummaryService summaryService;

    @InjectMocks BudgetCycleService service;

    // ---- calculateCycleDates ----

    @Test
    @DisplayName("startDay=1 → ciclo calendário (1º ao último dia do mês)")
    void calculateCycleDates_startDayOne_calendarioCiclo() {
        LocalDate[] dates = service.calculateCycleDates(YearMonth.of(2026, 6), 1);
        assertThat(dates[0]).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(dates[1]).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    @DisplayName("startDay=11, junho → 11/mai a 10/jun")
    void calculateCycleDates_startDay11_abrangeDoiMeses() {
        LocalDate[] dates = service.calculateCycleDates(YearMonth.of(2026, 6), 11);
        assertThat(dates[0]).isEqualTo(LocalDate.of(2026, 5, 11));
        assertThat(dates[1]).isEqualTo(LocalDate.of(2026, 6, 10));
    }

    @Test
    @DisplayName("startDay=28 → 28/mai a 27/jun")
    void calculateCycleDates_startDay28() {
        LocalDate[] dates = service.calculateCycleDates(YearMonth.of(2026, 6), 28);
        assertThat(dates[0]).isEqualTo(LocalDate.of(2026, 5, 28));
        assertThat(dates[1]).isEqualTo(LocalDate.of(2026, 6, 27));
    }

    @Test
    @DisplayName("startDay=11, janeiro → virada de ano (11/dez ano anterior a 10/jan)")
    void calculateCycleDates_viradaDeAno() {
        LocalDate[] dates = service.calculateCycleDates(YearMonth.of(2026, 1), 11);
        assertThat(dates[0]).isEqualTo(LocalDate.of(2025, 12, 11));
        assertThat(dates[1]).isEqualTo(LocalDate.of(2026, 1, 10));
    }

    @Test
    @DisplayName("startDay=1, fevereiro → 28 dias em 2026")
    void calculateCycleDates_fevereiro() {
        LocalDate[] dates = service.calculateCycleDates(YearMonth.of(2026, 2), 1);
        assertThat(dates[0]).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(dates[1]).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    // ---- open() — validações ----

    @Test
    @DisplayName("open() lança IllegalStateException se já existe ciclo OPEN")
    void open_jáExisteCicloAberto_lançaException() {
        Tenant tenant = tenantWith(1);
        User user = new User();

        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN))
            .thenReturn(Optional.of(new BudgetCycle()));

        assertThatThrownBy(() -> service.open(tenant, user, new BudgetCycleOpenRequest("2026-06", 1, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Já existe um ciclo aberto para este tenant.");
    }

    @Test
    @DisplayName("open() lança IllegalStateException se período sobrepõe ciclo existente")
    void open_periodoSobrepoe_lançaException() {
        Tenant tenant = tenantWith(1);
        User user = new User();

        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN))
            .thenReturn(Optional.empty());
        when(cycleRepository.existsOverlap(eq(tenant), any(), any()))
            .thenReturn(true);

        assertThatThrownBy(() -> service.open(tenant, user, new BudgetCycleOpenRequest("2026-06", 1, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("O período solicitado conflita com um ciclo já existente.");
    }

    @Test
    @DisplayName("open() calcula openingBalance a partir das contas líquidas do tenant")
    void open_calculaOpeningBalance() {
        Tenant tenant = tenantWith(1);
        User user = new User();

        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN))
            .thenReturn(Optional.empty());
        when(cycleRepository.existsOverlap(any(), any(), any()))
            .thenReturn(false);
        when(tenantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.sumLiquidBalanceByTenant(
                eq(tenant.getId()), eq(TransactionType.INCOME), eq(TransactionStatus.PAID)))
            .thenReturn(new BigDecimal("3200.00"));
        when(recurringRepository.findAllByTenantAndActiveTrueOrderByDayOfMonthAscDescriptionAsc(tenant))
            .thenReturn(List.of());
        when(transactionRepository.findInstallmentsInPeriodByTenant(any(), any(), any(), any()))
            .thenReturn(List.of());

        var captor = ArgumentCaptor.forClass(BudgetCycle.class);
        when(cycleRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.open(tenant, user, new BudgetCycleOpenRequest("2026-06", 1, null));

        assertThat(captor.getValue().getOpeningBalance()).isEqualByComparingTo("3200.00");
    }

    // ---- calculateExpectedDate ----

    @Test
    @DisplayName("dayOfMonth >= startDay → 1º mês do ciclo")
    void calculateExpectedDate_dayGeStartDay() {
        LocalDate startDate = LocalDate.of(2026, 5, 11);
        LocalDate result = service.calculateExpectedDate(startDate, 11, 15);
        assertThat(result).isEqualTo(LocalDate.of(2026, 5, 15));
    }

    @Test
    @DisplayName("dayOfMonth < startDay → 2º mês do ciclo")
    void calculateExpectedDate_dayLtStartDay() {
        LocalDate startDate = LocalDate.of(2026, 5, 11);
        LocalDate result = service.calculateExpectedDate(startDate, 11, 5);
        assertThat(result).isEqualTo(LocalDate.of(2026, 6, 5));
    }

    // ---- close() — snapshot e validações ----

    @Test
    @DisplayName("close() persiste snapshot com valores corretos do resumo")
    void close_persistsSnapshot() {
        Tenant tenant = tenantWith(1);
        UUID cycleId = UUID.randomUUID();
        BudgetCycle cycle = BudgetCycle.builder()
            .id(cycleId)
            .tenant(tenant)
            .startDate(LocalDate.of(2026, 6, 1))
            .endDate(LocalDate.of(2026, 6, 30))
            .openingBalance(new BigDecimal("1000.00"))
            .status(BudgetCycleStatus.OPEN)
            .build();

        List<BudgetItem> items = List.of();
        BudgetCycleSummaryDTO summary = new BudgetCycleSummaryDTO(
            new BigDecimal("1000.00"),  // openingBalance
            new BigDecimal("6000.00"),  // plannedIncome
            new BigDecimal("3500.00"),  // plannedExpense
            new BigDecimal("3500.00"),  // projectedBalance
            new BigDecimal("5000.00"),  // realizedIncome
            new BigDecimal("2000.00"),  // realizedExpense
            new BigDecimal("200.00"),   // unplannedExpenses
            new BigDecimal("2300.00"),  // availableToSpend
            new BigDecimal("115.00"),   // dailyAllowance
            20,                         // remainingDays
            2L                          // pendingCount
        );

        when(cycleRepository.findById(cycleId)).thenReturn(Optional.of(cycle));
        when(itemRepository.findAllByCycleWithDetails(cycle)).thenReturn(items);
        when(summaryService.calculateSummary(eq(cycle), eq(items), any(LocalDate.class))).thenReturn(summary);
        when(cycleRepository.save(any(BudgetCycle.class))).thenAnswer(inv -> inv.getArgument(0));

        BudgetCycle result = service.close(cycleId, tenant);

        assertThat(result.getStatus()).isEqualTo(BudgetCycleStatus.CLOSED);
        assertThat(result.getSnapshotProjectedBalance()).isEqualByComparingTo("3500");
        assertThat(result.getSnapshotAvailableToSpend()).isEqualByComparingTo("2300");
        assertThat(result.getSnapshotRealizedIncome()).isEqualByComparingTo("5000");
        assertThat(result.getSnapshotRealizedExpense()).isEqualByComparingTo("2000");
        assertThat(result.getSnapshotUnplannedExpenses()).isEqualByComparingTo("200");
        verify(cycleRepository).save(cycle);
    }

    @Test
    @DisplayName("close() em ciclo já CLOSED lança IllegalStateException")
    void close_alreadyClosed_throwsIllegalState() {
        Tenant tenant = tenantWith(1);
        BudgetCycle cycle = BudgetCycle.builder()
            .id(UUID.randomUUID())
            .tenant(tenant)
            .startDate(LocalDate.of(2026, 6, 1))
            .endDate(LocalDate.of(2026, 6, 30))
            .openingBalance(BigDecimal.ZERO)
            .status(BudgetCycleStatus.CLOSED)
            .build();

        when(cycleRepository.findById(cycle.getId())).thenReturn(Optional.of(cycle));

        assertThatThrownBy(() -> service.close(cycle.getId(), tenant))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("O ciclo já está fechado.");
    }

    // ---- helpers ----

    private Tenant tenantWith(int startDay) {
        Tenant t = new Tenant();
        t.setId(UUID.randomUUID());
        t.setBudgetCycleStartDay(startDay);
        return t;
    }
}
