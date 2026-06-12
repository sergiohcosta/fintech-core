package com.fintech.api.service;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.budget.RecurringBudgetItem;
import com.fintech.api.domain.enums.*;
import com.fintech.api.domain.installment.InstallmentGroup;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.BudgetCycleOpenRequest;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetCycleService {

    private final BudgetCycleRepository cycleRepository;
    private final BudgetItemRepository itemRepository;
    private final RecurringBudgetItemRepository recurringRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TenantRepository tenantRepository;

    /**
     * Calcula as datas de início e fim do ciclo para um dado mês de referência.
     *
     * Quando startDay=1, o ciclo coincide com o mês calendário (1º ao último dia).
     * Para outros valores, o ciclo começa no startDay do mês anterior e termina
     * no dia (startDay - 1) do mês de referência.
     *
     * Exemplo: startDay=11, referência=jun/2026 → 11/mai a 10/jun
     */
    LocalDate[] calculateCycleDates(YearMonth referenceMonth, int startDay) {
        if (startDay == 1) {
            return new LocalDate[]{referenceMonth.atDay(1), referenceMonth.atEndOfMonth()};
        }
        return new LocalDate[]{
            referenceMonth.minusMonths(1).atDay(startDay),
            referenceMonth.atDay(startDay - 1)
        };
    }

    /**
     * Determina a data esperada de um item recorrente dentro do ciclo.
     *
     * Se dayOfMonth >= startDay, a despesa/receita cai no primeiro mês do ciclo.
     * Caso contrário, cai no segundo mês (após a virada do ciclo).
     *
     * Exemplo: ciclo 11/mai–10/jun, startDay=11
     *   - dayOfMonth=15 → 15/mai (mesmo mês do início)
     *   - dayOfMonth=5  → 5/jun  (mês seguinte)
     */
    LocalDate calculateExpectedDate(LocalDate cycleStartDate, int startDay, int dayOfMonth) {
        if (dayOfMonth >= startDay) {
            return cycleStartDate.withDayOfMonth(dayOfMonth);
        }
        return cycleStartDate.plusMonths(1).withDayOfMonth(dayOfMonth);
    }

    /**
     * Abre um novo ciclo de planejamento para o tenant.
     *
     * Validações:
     * - Não pode existir outro ciclo OPEN para o mesmo tenant
     * - O período calculado não pode sobrepor ciclos já existentes
     *
     * Após criar o ciclo, popula automaticamente:
     * - Itens recorrentes (RecurringBudgetItem ativos do tenant)
     * - Parcelas de cartão cujo vencimento cai no período do ciclo
     */
    @Transactional
    public BudgetCycle open(Tenant tenant, User user, BudgetCycleOpenRequest req) {
        // O Tenant vindo do SecurityContext é um proxy Hibernate da sessão do SecurityFilter
        // (já encerrada). Acessar campos não-ID como budgetCycleStartDay lançaria
        // LazyInitializationException. Recarregamos aqui para vinculá-lo à sessão atual.
        Tenant managed = tenantRepository.findById(tenant.getId())
            .orElseThrow(() -> new EntityNotFoundException("Tenant não encontrado."));

        if (cycleRepository.findByTenantAndStatus(managed, BudgetCycleStatus.OPEN).isPresent()) {
            throw new IllegalStateException("Já existe um ciclo aberto para este tenant.");
        }

        int startDay = req.startDay();
        LocalDate[] dates = calculateCycleDates(YearMonth.parse(req.referenceMonth()), startDay);
        LocalDate startDate = dates[0];
        LocalDate endDate   = dates[1];

        if (cycleRepository.existsOverlap(managed, startDate, endDate)) {
            throw new IllegalStateException("O período solicitado conflita com um ciclo já existente.");
        }

        // Persiste a preferência de dia de início no tenant para próximos ciclos
        managed.setBudgetCycleStartDay(startDay);
        tenantRepository.save(managed);

        BigDecimal opening = accountRepository.sumLiquidBalanceByTenant(
            managed.getId(), TransactionType.INCOME, TransactionStatus.CANCELLED);

        BudgetCycle cycle = cycleRepository.save(BudgetCycle.builder()
            .tenant(managed)
            .startDate(startDate)
            .endDate(endDate)
            .openingBalance(opening)
            .status(BudgetCycleStatus.OPEN)
            .createdBy(user)
            .build());

        populateRecurringItems(cycle, managed, user, startDate, startDay);
        populateInstallmentItems(cycle, managed, startDate, endDate);

        log.info("Ciclo de planejamento aberto [cycleId={} tenantId={} periodo={}/{} startDay={}]",
            cycle.getId(), managed.getId(), startDate, endDate, startDay);
        return cycle;
    }

    private void populateRecurringItems(BudgetCycle cycle, Tenant tenant, User user,
                                        LocalDate startDate, int startDay) {
        List<RecurringBudgetItem> templates =
            recurringRepository.findAllByTenantAndActiveTrueOrderByDayOfMonthAscDescriptionAsc(tenant);

        List<BudgetItem> items = templates.stream()
            .map(t -> BudgetItem.builder()
                .cycle(cycle)
                .tenant(tenant)
                .description(t.getDescription())
                .amount(t.getAmount())
                .type(t.getType())
                .category(t.getCategory())
                .account(t.getAccount())
                .expectedDate(calculateExpectedDate(startDate, startDay, t.getDayOfMonth()))
                .source(BudgetItemSource.RECURRING)
                .recurringItem(t)
                .createdBy(user)
                .build())
            .toList();

        itemRepository.saveAll(items);
    }

    private void populateInstallmentItems(BudgetCycle cycle, Tenant tenant,
                                          LocalDate startDate, LocalDate endDate) {
        List<Transaction> installments = transactionRepository.findInstallmentsInPeriodByTenant(
            tenant.getId(), startDate, endDate, TransactionStatus.CANCELLED);

        // Agrupa parcelas pelo InstallmentGroup para criar um único BudgetItem por grupo
        Map<InstallmentGroup, List<Transaction>> byGroup = installments.stream()
            .collect(Collectors.groupingBy(Transaction::getInstallmentGroup));

        List<BudgetItem> items = byGroup.entrySet().stream()
            .map(entry -> {
                InstallmentGroup group = entry.getKey();
                List<Transaction> txs = entry.getValue();
                BigDecimal total = txs.stream()
                    .map(Transaction::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                // Data esperada = vencimento da fatura (data que impacta o orçamento)
                LocalDate dueDate = txs.get(0).getInvoice().getDueDate();
                return BudgetItem.builder()
                    .cycle(cycle)
                    .tenant(tenant)
                    .description(group.getDescription())
                    .amount(total)
                    .type(TransactionType.EXPENSE)
                    .account(group.getAccount())
                    .category(group.getCategory())
                    .expectedDate(dueDate)
                    .source(BudgetItemSource.INSTALLMENT)
                    .installmentGroup(group)
                    .build();
            })
            .toList();

        itemRepository.saveAll(items);
    }

    /**
     * Fecha um ciclo aberto (OPEN → CLOSED).
     * Operação administrativa sem side effects — registra que o planejamento foi encerrado.
     */
    @Transactional
    public BudgetCycle close(UUID cycleId, Tenant tenant) {
        BudgetCycle cycle = findByIdAndTenant(cycleId, tenant);
        if (cycle.getStatus() != BudgetCycleStatus.OPEN) {
            throw new IllegalStateException("O ciclo já está fechado.");
        }
        cycle.setStatus(BudgetCycleStatus.CLOSED);
        log.info("Ciclo fechado [cycleId={} tenantId={}]", cycleId, tenant.getId());
        return cycleRepository.save(cycle);
    }

    /**
     * Re-sincroniza os itens de parcelamento do ciclo.
     * Remove todos os itens com source=INSTALLMENT e os recria com base nas parcelas atuais.
     * Útil quando novas compras parceladas são adicionadas após a abertura do ciclo.
     */
    @Transactional
    public BudgetCycle syncInstallments(UUID cycleId, Tenant tenant, User user) {
        BudgetCycle cycle = findByIdAndTenant(cycleId, tenant);
        List<BudgetItem> existing = itemRepository.findAllByCycleWithDetails(cycle);
        List<BudgetItem> toRemove = existing.stream()
            .filter(i -> i.getSource() == BudgetItemSource.INSTALLMENT)
            .toList();
        itemRepository.deleteAll(toRemove);
        populateInstallmentItems(cycle, tenant, cycle.getStartDate(), cycle.getEndDate());
        return cycle;
    }

    @Transactional(readOnly = true)
    public Optional<BudgetCycle> findOpenByTenant(Tenant tenant) {
        return cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN);
    }

    @Transactional(readOnly = true)
    public BudgetCycle findByIdAndTenant(UUID id, Tenant tenant) {
        return cycleRepository.findById(id)
            .filter(c -> c.getTenant().getId().equals(tenant.getId()))
            .orElseThrow(() -> new EntityNotFoundException("Ciclo de planejamento não encontrado."));
    }

    @Transactional(readOnly = true)
    public Page<BudgetCycle> listByTenant(Tenant tenant, Pageable pageable) {
        return cycleRepository.findAllByTenantOrderByStartDateDesc(tenant, pageable);
    }

    @Transactional(readOnly = true)
    public List<BudgetItem> listItems(BudgetCycle cycle) {
        return itemRepository.findAllByCycleWithDetails(cycle);
    }
}
