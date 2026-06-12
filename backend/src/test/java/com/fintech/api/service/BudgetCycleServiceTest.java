package com.fintech.api.service;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.BudgetCycleOpenRequest;
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

        assertThatThrownBy(() -> service.open(tenant, user, new BudgetCycleOpenRequest("2026-06", 1)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ciclo aberto");
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

        assertThatThrownBy(() -> service.open(tenant, user, new BudgetCycleOpenRequest("2026-06", 1)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("conflita");
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
                eq(tenant.getId()), eq(TransactionType.INCOME), eq(TransactionStatus.CANCELLED)))
            .thenReturn(new BigDecimal("3200.00"));
        when(recurringRepository.findAllByTenantAndActiveTrueOrderByDayOfMonthAscDescriptionAsc(tenant))
            .thenReturn(List.of());
        when(transactionRepository.findInstallmentsInPeriodByTenant(any(), any(), any(), any()))
            .thenReturn(List.of());

        var captor = ArgumentCaptor.forClass(BudgetCycle.class);
        when(cycleRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.open(tenant, user, new BudgetCycleOpenRequest("2026-06", 1));

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

    private Tenant tenantWith(int startDay) {
        Tenant t = new Tenant();
        t.setId(UUID.randomUUID());
        t.setBudgetCycleStartDay(startDay);
        return t;
    }
}
