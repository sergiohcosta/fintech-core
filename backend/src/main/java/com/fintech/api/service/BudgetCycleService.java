package com.fintech.api.service;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.budget.RecurringBudgetItem;
import com.fintech.api.domain.enums.*;
import com.fintech.api.dto.budget.BudgetCycleResponseDTO;
import com.fintech.api.dto.budget.BudgetCycleSummaryDTO;
import com.fintech.api.domain.installment.InstallmentGroup;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
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
    private final BudgetSummaryService summaryService;

    /**
     * Calcula as datas de início e fim do ciclo para um dado mês de referência.
     *
     * O ciclo começa no dia {@code startDay} do mês de referência e termina na
     * véspera do mesmo dia no mês seguinte. Com startDay=1 isso coincide
     * naturalmente com o mês calendário (1º ao último dia) — sem caso especial.
     *
     * startDay é validado em 1..28 ({@code TenantSettingsPatchRequest}), logo
     * {@code atDay(startDay)} nunca estoura (todo mês tem ≥ 28 dias).
     *
     * Exemplo: startDay=11, referência=jun/2026 → 11/jun a 10/jul
     */
    LocalDate[] calculateCycleDates(YearMonth referenceMonth, int startDay) {
        LocalDate start = referenceMonth.atDay(startDay);
        return new LocalDate[]{ start, start.plusMonths(1).minusDays(1) };
    }

    /**
     * Determina a data esperada de um item recorrente dentro do ciclo.
     *
     * Se dayOfMonth >= startDay, a despesa/receita cai no primeiro mês do ciclo.
     * Caso contrário, cai no segundo mês (após a virada do ciclo).
     *
     * Exemplo: ciclo 11/jun–10/jul, startDay=11
     *   - dayOfMonth=15 → 15/jun (mesmo mês do início)
     *   - dayOfMonth=5  → 5/jul  (mês seguinte)
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
    public BudgetCycle open(Tenant tenant, User user, String referenceMonth) {
        if (cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN).isPresent()) {
            throw new IllegalStateException("Já existe um ciclo aberto para este tenant.");
        }

        int startDay = tenant.getBudgetCycleStartDay();
        LocalDate[] dates = calculateCycleDates(YearMonth.parse(referenceMonth), startDay);
        LocalDate startDate = dates[0];
        LocalDate endDate   = dates[1];

        if (cycleRepository.existsOverlap(tenant, startDate, endDate)) {
            throw new IllegalStateException("O período solicitado conflita com um ciclo já existente.");
        }

        // openingBalance = caixa líquido PAID acumulado ATÉ a véspera do ciclo (date < startDate).
        // Garante que transações dentro do período só sejam contadas via realized/unplanned.
        BigDecimal opening = accountRepository.sumLiquidBalanceByTenant(
            tenant.getId(), TransactionType.INCOME, TransactionStatus.PAID, startDate);

        BudgetCycle cycle = cycleRepository.save(BudgetCycle.builder()
            .tenant(tenant)
            .startDate(startDate)
            .endDate(endDate)
            .openingBalance(opening)
            .status(BudgetCycleStatus.OPEN)
            .createdBy(user)
            .build());

        populateRecurringItems(cycle, tenant, user, startDate, startDay);
        populateInstallmentItems(cycle, tenant, startDate, endDate);

        log.info("Ciclo de planejamento aberto [cycleId={} tenantId={} periodo={}/{}]",
            cycle.getId(), tenant.getId(), startDate, endDate);
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
        YearMonth invoiceMonth = YearMonth.from(startDate);
        List<Transaction> installments = transactionRepository.findInstallmentsByTenantAndInvoiceMonth(
            tenant.getId(), invoiceMonth.getYear(), invoiceMonth.getMonthValue(),
            TransactionStatus.CANCELLED);

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
     * Se o ciclo ainda está em andamento (hoje <= endDate) e force=false, lança 409.
     * Use force=true para fechar mesmo com o ciclo ativo (requer confirmação explícita).
     */
    @Transactional
    public BudgetCycle close(UUID cycleId, Tenant tenant, boolean force) {
        BudgetCycle cycle = loadByIdAndTenant(cycleId, tenant);
        if (cycle.getStatus() == BudgetCycleStatus.CLOSED) {
            throw new IllegalStateException("O ciclo já está fechado.");
        }
        if (!force && !LocalDate.now().isAfter(cycle.getEndDate())) {
            throw new IllegalStateException("O ciclo ainda está em andamento.");
        }
        cycle.setStatus(BudgetCycleStatus.CLOSED);
        log.info("Ciclo fechado [cycleId={} tenantId={} force={}]", cycleId, tenant.getId(), force);
        return cycleRepository.save(cycle);
    }

    @Transactional
    public void delete(UUID cycleId, Tenant tenant) {
        BudgetCycle cycle = loadByIdAndTenant(cycleId, tenant);
        if (cycle.getStatus() != BudgetCycleStatus.CLOSED) {
            throw new IllegalStateException("Apenas ciclos fechados podem ser excluídos.");
        }
        itemRepository.deleteAllByCycle(cycle);
        cycleRepository.delete(cycle);
        log.info("Ciclo excluído [cycleId={} tenantId={}]", cycleId, tenant.getId());
    }

    /**
     * Re-sincroniza os itens de parcelamento do ciclo.
     * Remove todos os itens com source=INSTALLMENT e os recria com base nas parcelas atuais.
     * Útil quando novas compras parceladas são adicionadas após a abertura do ciclo.
     */
    @Transactional
    public BudgetCycle syncInstallments(UUID cycleId, Tenant tenant, User user) {
        BudgetCycle cycle = loadByIdAndTenant(cycleId, tenant);
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
        return loadByIdAndTenant(id, tenant);
    }

    /**
     * Lookup interno SEM @Transactional. Chamado pelos métodos read-write deste service
     * (close/delete/syncInstallments), que já abrem transação própria — aqui o find apenas
     * participa dela. Evita o self-invocation de um método @Transactional via 'this', que
     * passaria por fora do proxy do Spring e ignoraria a anotação (regra S6809). O finder
     * público acima mantém o @Transactional(readOnly=true) para quando é ponto de entrada
     * (chamado pelo controller via proxy).
     */
    private BudgetCycle loadByIdAndTenant(UUID id, Tenant tenant) {
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

    /**
     * Monta o DTO completo do ciclo incluindo transações sem budget_item vinculado.
     * Centraliza a conversão para evitar duplicação nos callers do controller.
     */
    @Transactional(readOnly = true)
    public BudgetCycleResponseDTO toResponseDTO(BudgetCycle cycle) {
        List<BudgetItem> items = itemRepository.findAllByCycleWithDetails(cycle);
        YearMonth invoiceMonth = YearMonth.from(cycle.getStartDate());
        List<Transaction> unplanned = transactionRepository.findUnplannedByCycle(
            cycle.getTenant(), cycle,
            cycle.getStartDate(), cycle.getEndDate(),
            invoiceMonth.getYear(), invoiceMonth.getMonthValue(),
            TransactionStatus.CANCELLED
        );
        // Cálculo do resumo é responsabilidade do service (DTO é só mapeador).
        BudgetCycleSummaryDTO summary =
            summaryService.calculateSummary(cycle, items, unplanned, LocalDate.now());
        return BudgetCycleResponseDTO.fromEntity(cycle, items, unplanned, summary);
    }
}
