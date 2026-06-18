package com.fintech.api.service;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
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

        assertThatThrownBy(() -> service.open(tenant, user, "2026-06"))
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

        assertThatThrownBy(() -> service.open(tenant, user, "2026-06"))
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
        when(transactionRepository.findInstallmentsByTenantAndInvoiceMonth(any(), anyInt(), anyInt(), any()))
            .thenReturn(List.of());

        var captor = ArgumentCaptor.forClass(BudgetCycle.class);
        when(cycleRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.open(tenant, user, "2026-06");

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
            .startDate(LocalDate.of(2026, 5, 1))
            .endDate(LocalDate.of(2026, 5, 31))
            .openingBalance(new BigDecimal("1000.00"))
            .status(BudgetCycleStatus.ENDED)
            .build();

        List<BudgetItem> items = List.of();
        BudgetCycleSummaryDTO summary = new BudgetCycleSummaryDTO(
            new BigDecimal("6000.00"),  // plannedIncome
            new BigDecimal("3500.00"),  // plannedExpense
            new BigDecimal("3500.00"),  // projectedBalance
            new BigDecimal("5000.00"),  // realizedIncome
            new BigDecimal("2000.00"),  // realizedExpense
            new BigDecimal("3800.00"),  // currentBalance
            2L,                         // pendingCount
            BigDecimal.ZERO,            // unplannedIncome
            new BigDecimal("200.00"),   // unplannedExpense
            new BigDecimal("2300.00"),  // availableToSpend
            new BigDecimal("115.00"),   // dailyAllowance
            20                          // remainingDays
        );

        when(cycleRepository.findById(cycleId)).thenReturn(Optional.of(cycle));
        when(itemRepository.findAllByCycleWithDetails(cycle)).thenReturn(items);
        when(transactionRepository.findUnplannedByCycle(any(), any(), any(), any(), anyInt(), anyInt(), any()))
            .thenReturn(List.of());
        when(summaryService.calculateSummary(eq(cycle), eq(items), any(), any(LocalDate.class))).thenReturn(summary);
        when(cycleRepository.save(any(BudgetCycle.class))).thenAnswer(inv -> inv.getArgument(0));

        BudgetCycle result = service.close(cycleId, tenant, false);

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

        assertThatThrownBy(() -> service.close(cycle.getId(), tenant, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("O ciclo já está fechado.");
    }

    // ---- findCurrentByTenant() — lazy ENDED transition ----

    @Test
    @DisplayName("findCurrentByTenant() transita OPEN→ENDED quando endDate < today")
    void findCurrentByTenant_openComDataPassada_transitaParaEnded() {
        Tenant tenant = tenantWith(1);
        BudgetCycle cycle = BudgetCycle.builder()
            .id(UUID.randomUUID()).tenant(tenant)
            .startDate(LocalDate.of(2026, 5, 1))
            .endDate(LocalDate.of(2026, 5, 31))
            .openingBalance(BigDecimal.ZERO)
            .status(BudgetCycleStatus.OPEN)
            .build();

        when(cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN))
            .thenReturn(Optional.of(cycle));
        when(cycleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<BudgetCycle> result = service.findOpenByTenant(tenant);

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(BudgetCycleStatus.ENDED);
        verify(cycleRepository).save(cycle);
    }

    @Test
    @DisplayName("findCurrentByTenant() retorna ENDED quando não há OPEN")
    void findCurrentByTenant_semOpen_retornaEnded() {
        Tenant tenant = tenantWith(1);
        BudgetCycle ended = BudgetCycle.builder()
            .id(UUID.randomUUID()).tenant(tenant)
            .startDate(LocalDate.of(2026, 5, 1))
            .endDate(LocalDate.of(2026, 5, 31))
            .openingBalance(BigDecimal.ZERO)
            .status(BudgetCycleStatus.ENDED)
            .build();

        when(cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN))
            .thenReturn(Optional.empty());
        when(cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.ENDED))
            .thenReturn(Optional.of(ended));

        Optional<BudgetCycle> result = service.findOpenByTenant(tenant);

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(BudgetCycleStatus.ENDED);
    }

    // ---- close() — requer ENDED ----

    @Test
    @DisplayName("close() em ciclo OPEN lança IllegalStateException")
    void close_cicloOpen_lancaException() {
        Tenant tenant = tenantWith(1);
        BudgetCycle cycle = BudgetCycle.builder()
            .id(UUID.randomUUID()).tenant(tenant)
            .startDate(LocalDate.now().minusDays(5))
            .endDate(LocalDate.now().plusDays(25))
            .openingBalance(BigDecimal.ZERO)
            .status(BudgetCycleStatus.OPEN)
            .build();

        when(cycleRepository.findById(cycle.getId())).thenReturn(Optional.of(cycle));

        assertThatThrownBy(() -> service.close(cycle.getId(), tenant, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("O ciclo ainda está em andamento.");
    }

    @Test
    @DisplayName("close() em ciclo OPEN com force=true persiste CLOSED")
    void close_cicloOpenComForce_persisteFechado() {
        Tenant tenant = tenantWith(1);
        BudgetCycle cycle = BudgetCycle.builder()
            .id(UUID.randomUUID()).tenant(tenant)
            .startDate(LocalDate.now().minusDays(5))
            .endDate(LocalDate.now().plusDays(25))
            .openingBalance(BigDecimal.ZERO)
            .status(BudgetCycleStatus.OPEN)
            .build();

        when(cycleRepository.findById(cycle.getId())).thenReturn(Optional.of(cycle));
        when(itemRepository.findAllByCycleWithDetails(cycle)).thenReturn(List.of());
        when(transactionRepository.findUnplannedByCycle(any(), any(), any(), any(), anyInt(), anyInt(), any()))
            .thenReturn(List.of());
        when(summaryService.calculateSummary(eq(cycle), any(), any(), any(LocalDate.class))).thenReturn(
            new BudgetCycleSummaryDTO(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 0L,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null
            )
        );
        when(cycleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BudgetCycle result = service.close(cycle.getId(), tenant, true);

        assertThat(result.getStatus()).isEqualTo(BudgetCycleStatus.CLOSED);
    }

    @Test
    @DisplayName("close() em ciclo ENDED → persiste CLOSED com snapshot")
    void close_cicloEnded_persisteFechado() {
        Tenant tenant = tenantWith(1);
        BudgetCycle cycle = BudgetCycle.builder()
            .id(UUID.randomUUID()).tenant(tenant)
            .startDate(LocalDate.of(2026, 5, 1))
            .endDate(LocalDate.of(2026, 5, 31))
            .openingBalance(BigDecimal.ZERO)
            .status(BudgetCycleStatus.ENDED)
            .build();

        when(cycleRepository.findById(cycle.getId())).thenReturn(Optional.of(cycle));
        when(itemRepository.findAllByCycleWithDetails(cycle)).thenReturn(List.of());
        when(transactionRepository.findUnplannedByCycle(any(), any(), any(), any(), anyInt(), anyInt(), any()))
            .thenReturn(List.of());
        when(summaryService.calculateSummary(eq(cycle), any(), any(), any(LocalDate.class))).thenReturn(
            new BudgetCycleSummaryDTO(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 0L,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null
            )
        );
        when(cycleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BudgetCycle result = service.close(cycle.getId(), tenant, false);

        assertThat(result.getStatus()).isEqualTo(BudgetCycleStatus.CLOSED);
    }

    // ---- delete() ----

    @Test
    @DisplayName("delete() em ciclo CLOSED remove itens e ciclo")
    void delete_cicloFechado_removeItemsECiclo() {
        Tenant tenant = tenantWith(1);
        BudgetCycle cycle = BudgetCycle.builder()
            .id(UUID.randomUUID()).tenant(tenant)
            .startDate(LocalDate.of(2026, 5, 1))
            .endDate(LocalDate.of(2026, 5, 31))
            .openingBalance(BigDecimal.ZERO)
            .status(BudgetCycleStatus.CLOSED)
            .build();

        List<BudgetItem> items = List.of(
            BudgetItem.builder()
                .id(UUID.randomUUID())
                .cycle(cycle)
                .tenant(tenant)
                .description("Item teste")
                .amount(BigDecimal.TEN)
                .type(com.fintech.api.domain.enums.TransactionType.EXPENSE)
                .expectedDate(LocalDate.of(2026, 5, 10))
                .source(com.fintech.api.domain.enums.BudgetItemSource.MANUAL)
                .build()
        );

        when(cycleRepository.findById(cycle.getId())).thenReturn(Optional.of(cycle));
        when(itemRepository.findAllByCycleWithDetails(cycle)).thenReturn(items);

        service.delete(cycle.getId(), tenant);

        verify(itemRepository).deleteAll(items);
        verify(cycleRepository).delete(cycle);
    }

    @Test
    @DisplayName("delete() em ciclo não-CLOSED lança IllegalStateException")
    void delete_cicloNaoFechado_lancaException() {
        Tenant tenant = tenantWith(1);
        BudgetCycle cycle = BudgetCycle.builder()
            .id(UUID.randomUUID()).tenant(tenant)
            .startDate(LocalDate.of(2026, 6, 1))
            .endDate(LocalDate.of(2026, 6, 30))
            .openingBalance(BigDecimal.ZERO)
            .status(BudgetCycleStatus.ENDED)
            .build();

        when(cycleRepository.findById(cycle.getId())).thenReturn(Optional.of(cycle));

        assertThatThrownBy(() -> service.delete(cycle.getId(), tenant))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Apenas ciclos fechados podem ser excluídos.");
    }

    // ---- toResponseDTO ----

    @Test
    @DisplayName("toResponseDTO busca não planejados pelo mês da fatura (referenceYear/Month do startDate)")
    void toResponseDTO_chamaFindUnplannedComInvoiceMonth() {
        Tenant tenant = new Tenant();
        BudgetCycle cycle = BudgetCycle.builder()
            .id(UUID.randomUUID())
            .tenant(tenant)
            .startDate(LocalDate.of(2026, 6, 10))
            .endDate(LocalDate.of(2026, 7, 9))
            .openingBalance(BigDecimal.valueOf(5000))
            .status(BudgetCycleStatus.OPEN)
            .build();

        when(itemRepository.findAllByCycleWithDetails(cycle)).thenReturn(List.of());
        when(transactionRepository.findUnplannedByCycle(
            eq(tenant), eq(cycle),
            eq(LocalDate.of(2026, 6, 10)), eq(LocalDate.of(2026, 7, 9)),
            eq(2026), eq(6),
            eq(TransactionStatus.CANCELLED)
        )).thenReturn(List.of());
        when(summaryService.calculateSummary(eq(cycle), any(), any(), any(LocalDate.class)))
            .thenReturn(new BudgetCycleSummaryDTO(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(5000), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.valueOf(5000), 0L,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 20
            ));

        service.toResponseDTO(cycle);

        verify(transactionRepository).findUnplannedByCycle(
            tenant, cycle,
            LocalDate.of(2026, 6, 10), LocalDate.of(2026, 7, 9),
            2026, 6,
            TransactionStatus.CANCELLED
        );
    }

    // ---- helpers ----

    private Tenant tenantWith(int startDay) {
        Tenant t = new Tenant();
        t.setId(UUID.randomUUID());
        t.setBudgetCycleStartDay(startDay);
        return t;
    }
}
