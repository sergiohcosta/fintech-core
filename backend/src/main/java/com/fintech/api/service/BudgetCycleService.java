package com.fintech.api.service;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.budget.RecurringBudgetItem;
import com.fintech.api.domain.enums.*;
import com.fintech.api.domain.installment.InstallmentGroup;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.*;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.*;
import org.springframework.security.access.AccessDeniedException;
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
    private final BudgetSummaryService summaryService;

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
     * Transita OPEN→ENDED se endDate < today. Operação idempotente e lazy.
     * Permite que o ciclo seja encerrado automaticamente sem intervenção do usuário,
     * preservando o estado histórico antes do fechamento formal (close).
     */
    @Transactional
    public BudgetCycle checkAndTransitionToEnded(BudgetCycle cycle) {
        if (cycle.getStatus() == BudgetCycleStatus.OPEN
                && cycle.getEndDate().isBefore(LocalDate.now())) {
            cycle.setStatus(BudgetCycleStatus.ENDED);
            cycleRepository.save(cycle);
            log.info("Ciclo transitado para ENDED [cycleId={}]", cycle.getId());
        }
        return cycle;
    }

    /**
     * Retorna o ciclo "atual" do tenant: OPEN (verificando lazy transition) ou ENDED.
     * Substitui findOpenByTenant() — agora retorna também ciclos ENDED recém-transitados.
     */
    @Transactional
    public Optional<BudgetCycle> findCurrentByTenant(Tenant tenant) {
        Optional<BudgetCycle> open = cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN);
        if (open.isPresent()) {
            return Optional.of(checkAndTransitionToEnded(open.get()));
        }
        return cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.ENDED);
    }

    /**
     * Abre um novo ciclo de planejamento para o tenant.
     *
     * Validações:
     * - Não pode existir outro ciclo OPEN para o mesmo tenant
     * - O período calculado deve compreender a data atual (evita abrir ciclos futuros/passados)
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
        LocalDate today     = LocalDate.now();

        // Garante que today está dentro do período — impede abertura de ciclos futuros ou retroativos
        if (today.isBefore(startDate) || today.isAfter(endDate)) {
            throw new IllegalStateException("O período do ciclo deve compreender a data atual.");
        }

        if (cycleRepository.existsOverlap(managed, startDate, endDate)) {
            throw new IllegalStateException("O período solicitado conflita com um ciclo já existente.");
        }

        // Persiste a preferência de dia de início no tenant para próximos ciclos
        managed.setBudgetCycleStartDay(startDay);
        tenantRepository.save(managed);

        BigDecimal opening = req.openingBalance() != null
            ? req.openingBalance()
            : accountRepository.sumLiquidBalanceByTenant(
                managed.getId(), TransactionType.INCOME, TransactionStatus.PAID);

        BudgetCycle cycle = cycleRepository.save(BudgetCycle.builder()
            .tenant(managed)
            .startDate(startDate)
            .endDate(endDate)
            .openingBalance(opening)
            .referenceMonth(req.referenceMonth())
            .status(BudgetCycleStatus.OPEN)
            .createdBy(user)
            .build());

        populateRecurringItems(cycle, managed, user, startDate, startDay);
        populateInstallmentItems(cycle, managed, startDate, endDate);

        log.info("Ciclo de planejamento aberto [cycleId={} tenantId={} periodo={}/{} startDay={}]",
            cycle.getId(), managed.getId(), startDate, endDate, startDay);
        return cycle;
    }

    /**
     * Retorna preview do ciclo que seria criado sem persistir nada.
     * Auto-determina referenceMonth a partir de today + startDay.
     * Útil para o frontend exibir um resumo antes de o usuário confirmar a abertura.
     */
    @Transactional(readOnly = true)
    public BudgetCyclePreviewDTO preview(Tenant tenant, int startDay) {
        Tenant managed = tenantRepository.findById(tenant.getId())
            .orElseThrow(() -> new EntityNotFoundException("Tenant não encontrado."));

        LocalDate today = LocalDate.now();
        YearMonth thisMonth = YearMonth.from(today);

        // Tenta este mês, depois o próximo, até encontrar o período que contém today
        LocalDate[] dates = null;
        YearMonth refMonth = null;
        for (int offset = 0; offset <= 1; offset++) {
            YearMonth candidate = thisMonth.plusMonths(offset);
            LocalDate[] d = calculateCycleDates(candidate, startDay);
            if (!today.isBefore(d[0]) && !today.isAfter(d[1])) {
                dates = d;
                refMonth = candidate;
                break;
            }
        }
        if (dates == null) {
            throw new IllegalStateException("Não foi possível determinar o ciclo atual para o dia de início informado.");
        }

        final LocalDate startDate = dates[0];
        final LocalDate endDate   = dates[1];
        final int sd = startDay;

        BigDecimal suggestedBalance = accountRepository.sumLiquidBalanceByTenant(
            managed.getId(), TransactionType.INCOME, TransactionStatus.PAID);

        List<RecurringBudgetItem> templates =
            recurringRepository.findAllByTenantAndActiveTrueOrderByDayOfMonthAscDescriptionAsc(managed);

        List<RecurringItemPreviewDTO> recurringPreviews = templates.stream()
            .map(t -> new RecurringItemPreviewDTO(
                t.getDescription(),
                t.getAmount(),
                t.getType().name(),
                calculateExpectedDate(startDate, sd, t.getDayOfMonth())))
            .toList();

        List<Transaction> installments = transactionRepository.findInstallmentsInPeriodByTenant(
            managed.getId(), startDate, endDate, TransactionStatus.CANCELLED);

        Map<InstallmentGroup, List<Transaction>> byGroup = installments.stream()
            .collect(Collectors.groupingBy(Transaction::getInstallmentGroup));

        List<InstallmentItemPreviewDTO> installmentPreviews = byGroup.entrySet().stream()
            .map(entry -> {
                InstallmentGroup group = entry.getKey();
                BigDecimal total = entry.getValue().stream()
                    .map(Transaction::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                // findInstallmentsInPeriodByTenant garante invoice != null e installmentGroup != null
                LocalDate dueDate = entry.getValue().get(0).getInvoice().getDueDate();
                return new InstallmentItemPreviewDTO(group.getDescription(), total, dueDate);
            })
            .toList();

        BigDecimal projectedIncome = recurringPreviews.stream()
            .filter(r -> "INCOME".equals(r.type()))
            .map(RecurringItemPreviewDTO::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal projectedExpense = recurringPreviews.stream()
            .filter(r -> "EXPENSE".equals(r.type()))
            .map(RecurringItemPreviewDTO::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .add(installmentPreviews.stream()
                .map(InstallmentItemPreviewDTO::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        return new BudgetCyclePreviewDTO(
            refMonth.toString(), startDay, startDate, endDate, suggestedBalance,
            recurringPreviews, installmentPreviews, projectedIncome, projectedExpense
        );
    }

    /**
     * Fecha um ciclo ENDED (ENDED → CLOSED).
     * Calcula o resumo final e persiste os valores de snapshot para registro histórico.
     *
     * Requer ENDED (não OPEN) — o ciclo deve ter encerrado seu período antes de ser
     * fechado formalmente. Essa separação evita fechamentos prematuros e garante que
     * os snapshots reflitam o estado completo do ciclo.
     */
    @Transactional
    public BudgetCycle close(UUID cycleId, Tenant tenant) {
        BudgetCycle cycle = findByIdAndTenant(cycleId, tenant);

        if (cycle.getStatus() != BudgetCycleStatus.ENDED) {
            String msg = cycle.getStatus() == BudgetCycleStatus.OPEN
                ? "O ciclo ainda está em andamento."
                : "O ciclo já está fechado.";
            throw new IllegalStateException(msg);
        }

        List<BudgetItem> items = itemRepository.findAllByCycleWithDetails(cycle);
        BudgetCycleSummaryDTO summary = summaryService.calculateSummary(cycle, items, LocalDate.now());

        cycle.setSnapshotProjectedBalance(summary.projectedBalance());
        cycle.setSnapshotAvailableToSpend(summary.availableToSpend());
        cycle.setSnapshotRealizedIncome(summary.realizedIncome());
        cycle.setSnapshotRealizedExpense(summary.realizedExpense());
        cycle.setSnapshotUnplannedExpenses(summary.unplannedExpenses());
        cycle.setStatus(BudgetCycleStatus.CLOSED);

        log.info("Ciclo fechado [cycleId={} tenantId={}]", cycleId, tenant.getId());
        return cycleRepository.save(cycle);
    }

    /**
     * Exclui permanentemente um ciclo CLOSED e todos os seus itens.
     *
     * Requer CLOSED — ciclos OPEN ou ENDED ainda podem ser relevantes para o planejamento
     * corrente e não devem ser apagados acidentalmente. A exclusão deleta os BudgetItems
     * primeiro (FK NOT NULL sem CASCADE) e depois o próprio ciclo.
     */
    @Transactional
    public void delete(UUID cycleId, Tenant tenant) {
        BudgetCycle cycle = cycleRepository.findById(cycleId)
            .filter(c -> c.getTenant().getId().equals(tenant.getId()))
            .orElseThrow(() -> new AccessDeniedException("Acesso negado."));

        if (cycle.getStatus() != BudgetCycleStatus.CLOSED) {
            throw new IllegalStateException("Apenas ciclos fechados podem ser excluídos.");
        }

        itemRepository.deleteAll(itemRepository.findAllByCycleWithDetails(cycle));
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
        BudgetCycle cycle = findByIdAndTenant(cycleId, tenant);
        List<BudgetItem> existing = itemRepository.findAllByCycleWithDetails(cycle);
        itemRepository.deleteAll(existing.stream()
            .filter(i -> i.getSource() == BudgetItemSource.INSTALLMENT)
            .toList());
        populateInstallmentItems(cycle, tenant, cycle.getStartDate(), cycle.getEndDate());
        return cycle;
    }

    @Transactional
    public BudgetCycle findByIdAndTenant(UUID id, Tenant tenant) {
        BudgetCycle cycle = cycleRepository.findById(id)
            .filter(c -> c.getTenant().getId().equals(tenant.getId()))
            .orElseThrow(() -> new AccessDeniedException("Acesso negado."));
        return checkAndTransitionToEnded(cycle);
    }

    @Transactional(readOnly = true)
    public Page<BudgetCycle> listByTenant(Tenant tenant, Pageable pageable) {
        return cycleRepository.findAllByTenantOrderByStartDateDesc(tenant, pageable);
    }

    @Transactional(readOnly = true)
    public List<BudgetItem> listItems(BudgetCycle cycle) {
        return itemRepository.findAllByCycleWithDetails(cycle);
    }

    // ---- private helpers ----

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
}
