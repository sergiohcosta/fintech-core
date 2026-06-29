package com.fintech.api.service;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.enums.BudgetItemSource;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.recurrence.RecurrenceRule;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.BudgetCycleSummaryDTO;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.BudgetCycleRepository;
import com.fintech.api.repository.BudgetItemRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.RecurrenceRuleRepository;
import com.fintech.api.repository.TenantRepository;
import com.fintech.api.repository.TransactionRepository;
import com.fintech.api.service.recurrence.ProjectedOccurrence;
import com.fintech.api.service.recurrence.RecurrenceProjectionService;
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
    @Mock RecurrenceProjectionService recurrenceProjectionService;
    @Mock RecurrenceRuleRepository ruleRepository;
    @Mock AccountRepository accountRepository;
    @Mock CategoryRepository categoryRepository;
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
    @DisplayName("startDay=11, junho → 11/jun a 10/jul (ciclo inicia no mês de referência)")
    void calculateCycleDates_startDay11_iniciaNoMesDeReferencia() {
        LocalDate[] dates = service.calculateCycleDates(YearMonth.of(2026, 6), 11);
        assertThat(dates[0]).isEqualTo(LocalDate.of(2026, 6, 11));
        assertThat(dates[1]).isEqualTo(LocalDate.of(2026, 7, 10));
    }

    @Test
    @DisplayName("startDay=28 (máximo permitido) → 28/jun a 27/jul")
    void calculateCycleDates_startDay28() {
        LocalDate[] dates = service.calculateCycleDates(YearMonth.of(2026, 6), 28);
        assertThat(dates[0]).isEqualTo(LocalDate.of(2026, 6, 28));
        assertThat(dates[1]).isEqualTo(LocalDate.of(2026, 7, 27));
    }

    @Test
    @DisplayName("startDay=11, dezembro → virada de ano no fim do ciclo (11/dez a 10/jan do ano seguinte)")
    void calculateCycleDates_viradaDeAno() {
        LocalDate[] dates = service.calculateCycleDates(YearMonth.of(2026, 12), 11);
        assertThat(dates[0]).isEqualTo(LocalDate.of(2026, 12, 11));
        assertThat(dates[1]).isEqualTo(LocalDate.of(2027, 1, 10));
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

        when(cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN))
            .thenReturn(Optional.empty());
        when(cycleRepository.existsOverlap(any(), any(), any()))
            .thenReturn(false);
        // startDay=1 → ciclo de 2026-06; openingBalance soma só PAID com date < 2026-06-01.
        when(accountRepository.sumLiquidBalanceByTenant(
                eq(tenant.getId()), eq(TransactionType.INCOME), eq(TransactionStatus.PAID),
                eq(LocalDate.of(2026, 6, 1))))
            .thenReturn(new BigDecimal("3200.00"));
        when(recurrenceProjectionService.project(eq(tenant), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(List.of());
        when(transactionRepository.findInstallmentsByTenantAndInvoiceMonth(any(), anyInt(), anyInt(), any()))
            .thenReturn(List.of());

        var captor = ArgumentCaptor.forClass(BudgetCycle.class);
        when(cycleRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.open(tenant, user, "2026-06");

        assertThat(captor.getValue().getOpeningBalance()).isEqualByComparingTo("3200.00");
    }

    @Test
    @DisplayName("open() com regra projetada → cria BudgetItem com recurrenceRule e occurrenceDate preenchidos")
    void open_comRegra_criaBudgetItemComRecurrenceRuleId() {
        Tenant tenant = tenantWith(1);
        User user = new User();
        UUID ruleId = UUID.randomUUID();

        when(cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN))
            .thenReturn(Optional.empty());
        when(cycleRepository.existsOverlap(any(), any(), any())).thenReturn(false);
        when(accountRepository.sumLiquidBalanceByTenant(any(), any(), any(), any()))
            .thenReturn(BigDecimal.ZERO);
        when(cycleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Regra projeta 1 ocorrência no dia 10/06/2026
        var occurrence = new ProjectedOccurrence(
            ruleId, LocalDate.of(2026, 6, 10),
            "Salário", new BigDecimal("5000.00"),
            com.fintech.api.domain.enums.TransactionType.INCOME,
            null, null, null,
            UUID.randomUUID(), "Conta Corrente"
        );
        when(recurrenceProjectionService.project(eq(tenant), any(), any()))
            .thenReturn(List.of(occurrence));

        var ruleProxy = RecurrenceRule.builder().id(ruleId).build();
        when(ruleRepository.getReferenceById(ruleId)).thenReturn(ruleProxy);
        when(transactionRepository.findInstallmentsByTenantAndInvoiceMonth(any(), anyInt(), anyInt(), any()))
            .thenReturn(List.of());

        var itemCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        when(itemRepository.saveAll(itemCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.open(tenant, user, "2026-06");

        @SuppressWarnings("unchecked")
        var saved = (java.util.List<BudgetItem>) itemCaptor.getValue();
        assertThat(saved).hasSize(1);
        BudgetItem item = saved.get(0);
        assertThat(item.getRecurrenceRule().getId()).isEqualTo(ruleId);
        assertThat(item.getRecurrenceOccurrenceDate()).isEqualTo(LocalDate.of(2026, 6, 10));
        assertThat(item.getSource()).isEqualTo(BudgetItemSource.RECURRING);
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

        when(cycleRepository.findById(cycleId)).thenReturn(Optional.of(cycle));
        when(cycleRepository.save(any(BudgetCycle.class))).thenAnswer(inv -> inv.getArgument(0));

        BudgetCycle result = service.close(cycleId, tenant, false);

        assertThat(result.getStatus()).isEqualTo(BudgetCycleStatus.CLOSED);
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
    @DisplayName("findOpenByTenant() retorna ciclo OPEN quando existe")
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

        Optional<BudgetCycle> result = service.findOpenByTenant(tenant);

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(BudgetCycleStatus.OPEN);
    }

    @Test
    @DisplayName("findOpenByTenant() retorna vazio quando não há ciclo OPEN")
    void findCurrentByTenant_semOpen_retornaEnded() {
        Tenant tenant = tenantWith(1);

        when(cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN))
            .thenReturn(Optional.empty());

        Optional<BudgetCycle> result = service.findOpenByTenant(tenant);

        assertThat(result).isEmpty();
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

        when(cycleRepository.findById(cycle.getId())).thenReturn(Optional.of(cycle));

        service.delete(cycle.getId(), tenant);

        verify(itemRepository).deleteAllByCycle(cycle);
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
