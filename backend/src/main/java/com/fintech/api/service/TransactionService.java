package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.enums.DeleteInstallmentScope;
import com.fintech.api.domain.enums.InvoiceStatus;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.installment.InstallmentGroup;
import com.fintech.api.domain.invoice.Invoice;
import com.fintech.api.domain.recurrence.RecurrenceRule;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.service.recurrence.RecurrenceProjectionService;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.installment.DeleteInstallmentResultDTO;
import com.fintech.api.dto.transaction.InstallmentPreviewDTO;
import com.fintech.api.dto.transaction.InstallmentPreviewRequestDTO;
import com.fintech.api.dto.transaction.TransactionRequestDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.dto.transaction.TransactionUpdateDTO;
import com.fintech.api.dto.transfer.TransferRequestDTO;
import com.fintech.api.dto.transfer.TransferResponseDTO;
import com.fintech.api.exception.BusinessConflictException;
import com.fintech.api.exception.BusinessException;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.BudgetItemRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.CreditCardDetailsRepository;
import com.fintech.api.repository.InstallmentGroupRepository;
import com.fintech.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository repository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;
    private final InstallmentGroupRepository installmentGroupRepository;
    private final CreditCardDetailsRepository creditCardDetailsRepository;
    private final InvoiceService invoiceService;
    private final RecurrenceProjectionService projectionService;
    private final BudgetItemRepository budgetItemRepository;

    @Transactional(readOnly = true)
    public List<TransactionResponseDTO> findAll(User user, UUID invoiceId, List<UUID> accountIds,
            TransactionStatus status, TransactionType type, LocalDate startDate, LocalDate endDate) {
        return findAll(user, invoiceId, accountIds, status, type, startDate, endDate, false);
    }

    @Transactional(readOnly = true)
    public List<TransactionResponseDTO> findAll(User user, UUID invoiceId, List<UUID> accountIds,
            TransactionStatus status, TransactionType type, LocalDate startDate, LocalDate endDate,
            boolean includeProjected) {
        if ((startDate == null) != (endDate == null)) {
            throw new BusinessException("startDate e endDate devem ser informados juntos ou omitidos juntos");
        }
        if (invoiceId != null) {
            Invoice invoice = invoiceService.findByIdAndTenant(invoiceId, user.getTenant());
            return repository.findAllByTenantAndInvoiceWithDetails(user.getTenant(), invoice)
                    .stream().map(TransactionResponseDTO::fromEntity).toList(); // fatura não projeta
        }
        // Sentinelas substituem null para evitar IS NULL no JPQL com LocalDate.
        // PostgreSQL não consegue inferir o tipo de "? IS NULL" sem contexto de coluna.
        LocalDate effectiveStart = startDate != null ? startDate : LocalDate.of(1000, 1, 1);
        LocalDate effectiveEnd   = endDate   != null ? endDate   : LocalDate.of(9999, 12, 31);

        // Lista vazia ou null = sem filtro de conta (accountIdCount = 0 → condição ignorada no JPQL).
        // Lista com itens = filtra pelas contas informadas (accountIdCount > 0 → IN ativado).
        List<UUID> effectiveAccountIds = (accountIds == null || accountIds.isEmpty()) ? List.of() : accountIds;
        int accountIdCount = effectiveAccountIds.size();

        List<TransactionResponseDTO> reais = repository.findAllByTenantWithFilters(
                        user.getTenant(), effectiveAccountIds, accountIdCount, status, type, effectiveStart, effectiveEnd)
                .stream()
                .map(TransactionResponseDTO::fromEntity)
                .toList();

        // Projeção só faz sentido com janela explícita (a expansão RRULE sempre precisa de limites).
        if (!includeProjected || startDate == null || endDate == null) {
            return reais.stream()
                    .sorted(Comparator.comparing(this::effectiveSortDateDto, Comparator.reverseOrder())
                            .thenComparing(d -> d.createdAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        }

        // Mesmos filtros de conta/tipo aplicados em memória; fantasma é sempre PENDING.
        List<TransactionResponseDTO> fantasmas = projectionService
                .project(user.getTenant(), startDate, endDate).stream()
                .filter(o -> effectiveAccountIds.isEmpty() || effectiveAccountIds.contains(o.accountId()))
                .filter(o -> type == null || type == o.type())
                .filter(o -> status == null || status == TransactionStatus.PENDING)
                .map(TransactionResponseDTO::fromProjection)
                .toList();

        return Stream.concat(reais.stream(), fantasmas.stream())
                .sorted(Comparator.comparing(this::effectiveSortDateDto, Comparator.reverseOrder())
                            .thenComparing(d -> d.createdAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    // Para parcelas de cartão de crédito (installmentGroup presente), a posição na linha do
    // tempo é o dueDate da fatura — alinhado com a regra JPQL de filtro de período. Transações
    // avulsas de cartão (sem installmentGroup) usam t.date: a data de compra é única e é a
    // informação relevante. A regra opera sobre o DTO (reais já mapeados + fantasmas); fantasma
    // não tem installmentGroup, então cai no occurrenceDate (=date).
    private LocalDate effectiveSortDateDto(TransactionResponseDTO d) {
        if (d.installmentGroupId() != null && d.invoiceDueDate() != null) {
            return d.invoiceDueDate();
        }
        return d.date();
    }

    @Transactional(readOnly = true)
    public TransactionResponseDTO findById(UUID id, User user) {
        return TransactionResponseDTO.fromEntity(
                repository.findByIdAndTenant(id, user.getTenant())
                        .orElseThrow(() -> new EntityNotFoundException("Transação não encontrada.")));
    }

    @Transactional
    public List<TransactionResponseDTO> create(TransactionRequestDTO dto, User user) {
        // Manual: compra PARCELADA descarta parcelas que cairiam em fatura fechada/paga
        // (D1/D2). Transação avulsa (N=1) mantém o comportamento antigo — anexa à fatura
        // mesmo fechada/paga.
        return create(dto, user, null, true);
    }

    /**
     * Overload usado SÓ pelo commit de importação ({@code ImportService}): quando a fatura de
     * origem já é conhecida (documento com vencimento único impresso, ex. fatura Itaú), a
     * parcela-âncora (i=0) ignora {@code resolveInvoiceMonth} e usa {@code anchorInvoiceMonth}
     * diretamente — o documento já decidiu em que fatura a linha caiu; recalcular pela data de
     * compra reintroduz a mesma fragilidade que causou o roteamento errado de parcelas em
     * andamento (spec 2026-08-09-itau-fatura-ancora-por-documento). Parcelas futuras de um
     * parcelamento novo (i=1..N-1) seguem cascata normal a partir da âncora.
     * A âncora explícita do documento prevalece sobre o status da fatura: a importação NÃO
     * descarta parcelas (skipClosedInvoices=false, D1).
     */
    @Transactional
    public List<TransactionResponseDTO> create(
            TransactionRequestDTO dto, User user, YearMonth anchorInvoiceMonth) {
        return create(dto, user, anchorInvoiceMonth, false);
    }

    /**
     * Caminho único de criação (D1). {@code skipClosedInvoices=true} (manual e recorrência)
     * descarta parcelas que roteariam para fatura existente com status != OPEN — somente
     * quando há parcelamento (N>1); transação avulsa no cartão preserva o comportamento
     * histórico de anexar à fatura existente. A importação passa {@code false} e mantém o
     * roteamento do documento intacto.
     */
    private List<TransactionResponseDTO> create(TransactionRequestDTO dto, User user,
            YearMonth anchorInvoiceMonth, boolean skipClosedInvoices) {
        Category category = resolveCategory(dto.categoryId(), user);
        Account account = resolveAccount(dto.accountId(), user);

        int installments = (dto.totalInstallments() != null && dto.totalInstallments() > 1)
                ? dto.totalInstallments() : 1;
        // #136: dividir com DOWN e deixar a ÚLTIMA parcela absorver o resíduo garante
        // soma(parcelas) == total exatamente. HALF_EVEN uniforme perdia/ganhava centavos
        // (100/3 → 33,33×3 = 99,99). Invariante contábil: as parcelas derivam do total.
        BigDecimal installmentAmount = dto.amount()
                .divide(BigDecimal.valueOf(installments), 2, RoundingMode.DOWN);
        BigDecimal lastInstallmentAmount = dto.amount()
                .subtract(installmentAmount.multiply(BigDecimal.valueOf(installments - 1L)));

        boolean isCreditCard = AccountType.CREDIT_CARD.equals(account.getType());
        int closingDay = 0;
        if (isCreditCard) {
            // Buscado mesmo com anchorInvoiceMonth != null: closingDay só entra na conta no
            // ternário abaixo quando NÃO há âncora, mas esta busca também valida que a conta
            // TEM CreditCardDetails (senão lança EntityNotFoundException) — checagem que vale
            // nos dois caminhos, com ou sem âncora.
            closingDay = creditCardDetailsRepository.findByAccount(account)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Detalhes do cartão não encontrados para a conta."))
                    .getClosingDay();
        }
        final int finalClosingDay = closingDay;

        InstallmentGroup group = null;
        List<Transaction> created = new ArrayList<>();
        for (int i = 0; i < installments; i++) {
            Invoice invoice = null;
            LocalDate transactionDate;

            if (isCreditCard) {
                YearMonth invoiceMonth = (anchorInvoiceMonth != null
                        ? anchorInvoiceMonth
                        : resolveInvoiceMonth(dto.date(), finalClosingDay)).plusMonths(i);
                if (skipClosedInvoices && installments > 1) {
                    // Descarte SÓ em compra parcelada (N>1): avulsa em fatura fechada/paga
                    // continua anexando, como antes da feature. Leitura sem materialização:
                    // o descarte não pode nem criar a fatura (D2).
                    Invoice existing = invoiceService
                            .findExisting(account, invoiceMonth.getYear(), invoiceMonth.getMonthValue())
                            .orElse(null);
                    if (existing != null && existing.getStatus() != InvoiceStatus.OPEN) {
                        // Parcela descartada (D3): mantém a numeração original (i+1), não
                        // renumera nem cria transação — a parcela já foi paga fora do sistema.
                        continue;
                    }
                }
                invoice = invoiceService.getOrCreate(account, invoiceMonth.getYear(), invoiceMonth.getMonthValue());
                transactionDate = dto.date(); // data de compra igual em todas as parcelas
            } else {
                transactionDate = dto.date().plusMonths(i);
            }

            // Grupo criado lazy na primeira parcela efetivamente criada (D4): se todas forem
            // descartadas, nenhum grupo órfão fica no banco.
            if (group == null && installments > 1) {
                group = installmentGroupRepository.save(InstallmentGroup.builder()
                        .description(dto.description())
                        .totalAmount(dto.amount())
                        .totalInstallments(installments)
                        .account(account)
                        .category(category)
                        .tenant(user.getTenant())
                        .build());
            }

            created.add(repository.save(Transaction.builder()
                    .description(dto.description())
                    .amount(i == installments - 1 ? lastInstallmentAmount : installmentAmount)
                    .date(transactionDate)
                    .type(dto.type())
                    .status(dto.status() != null ? dto.status() : TransactionStatus.PENDING)
                    .installmentNumber(i + 1)
                    .totalInstallments(installments)
                    .installmentGroup(group)
                    .invoice(invoice)
                    .tenant(user.getTenant())
                    .user(user)
                    .category(category)
                    .account(account)
                    .build()));
        }
        return created.stream().map(TransactionResponseDTO::fromEntity).toList();
    }

    /**
     * Preview server-side do parcelamento (D5): devolve, por parcela, a fatura de destino
     * (mês/ano, fechamento, vencimento), o status atual e se o create a criaria
     * ({@code willCreate}). Read-only de propósito — não chama {@code getOrCreate}, não salva
     * nada. O frontend usa para pedir confirmação quando houver descarte; o backend continua
     * sendo a autoridade na gravação.
     */
    @Transactional(readOnly = true)
    public List<InstallmentPreviewDTO> previewInstallments(InstallmentPreviewRequestDTO dto, User user) {
        Account account = resolveAccount(dto.accountId(), user);
        if (!AccountType.CREDIT_CARD.equals(account.getType())) {
            throw new BusinessException(
                    "O preview de parcelamento só se aplica a contas de cartão de crédito.");
        }
        var cardDetails = creditCardDetailsRepository.findByAccount(account)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Detalhes do cartão não encontrados para a conta."));
        int closingDay = cardDetails.getClosingDay();
        int dueDay = cardDetails.getDueDay();

        int installments = (dto.totalInstallments() != null && dto.totalInstallments() > 1)
                ? dto.totalInstallments() : 1;
        // Mesma divisão de centavos do create (#136): o preview deve mostrar exatamente o
        // valor que cada parcela teria se criada.
        BigDecimal installmentAmount = dto.amount()
                .divide(BigDecimal.valueOf(installments), 2, RoundingMode.DOWN);
        BigDecimal lastInstallmentAmount = dto.amount()
                .subtract(installmentAmount.multiply(BigDecimal.valueOf(installments - 1L)));

        List<InstallmentPreviewDTO> preview = new ArrayList<>();
        for (int i = 0; i < installments; i++) {
            YearMonth invoiceMonth = resolveInvoiceMonth(dto.date(), closingDay).plusMonths(i);
            Invoice existing = invoiceService
                    .findExisting(account, invoiceMonth.getYear(), invoiceMonth.getMonthValue())
                    .orElse(null);
            // Fatura existente: usar as datas PERSISTIDAS — closingDay/dueDay do cartão
            // podem ter mudado desde a criação e o schedule recalculado divergiria da
            // fatura real. Só calcula quando a fatura ainda não existe.
            LocalDate closingDate;
            LocalDate dueDate;
            if (existing != null) {
                closingDate = existing.getClosingDate();
                dueDate = existing.getDueDate();
            } else {
                InvoiceService.InvoiceSchedule schedule = InvoiceService.scheduleFor(
                        invoiceMonth.getYear(), invoiceMonth.getMonthValue(), closingDay, dueDay);
                closingDate = schedule.closingDate();
                dueDate = schedule.dueDate();
            }
            boolean willCreate = existing == null || existing.getStatus() == InvoiceStatus.OPEN;
            preview.add(new InstallmentPreviewDTO(
                    i + 1,
                    installments,
                    i == installments - 1 ? lastInstallmentAmount : installmentAmount,
                    invoiceMonth.getYear(),
                    invoiceMonth.getMonthValue(),
                    closingDate,
                    dueDate,
                    existing != null ? existing.getId() : null,
                    existing != null ? existing.getStatus() : null,
                    willCreate));
        }
        return preview;
    }

    // Materializa UMA ocorrência de regra como transação real. Reusa a resolução de fatura
    // de cartão (resolveInvoiceMonth/getOrCreate) — se a conta da regra for CREDIT_CARD, a
    // transação nasce amarrada à fatura correta, sem caminho novo.
    @Transactional
    public TransactionResponseDTO materializeFromRule(
            RecurrenceRule rule, LocalDate occurrence, BigDecimal amountOverride, LocalDate dateOverride, User user) {
        Account account = rule.getAccount();
        BigDecimal amount = amountOverride != null ? amountOverride : rule.getBaseAmount();
        LocalDate date = dateOverride != null ? dateOverride : occurrence;

        Invoice invoice = null;
        if (AccountType.CREDIT_CARD.equals(account.getType())) {
            int closingDay = creditCardDetailsRepository.findByAccount(account)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Detalhes do cartão não encontrados para a conta."))
                    .getClosingDay();
            YearMonth invoiceMonth = resolveInvoiceMonth(date, closingDay);
            // D6: materializar é ação explícita do usuário — se a fatura da ocorrência já
            // fechou/pagou, recusar com 409 em vez de gravar silenciosamente (como o create
            // manual, que descarta; aqui não há "próxima parcela" para pular).
            Invoice existing = invoiceService
                    .findExisting(account, invoiceMonth.getYear(), invoiceMonth.getMonthValue())
                    .orElse(null);
            if (existing != null && existing.getStatus() != InvoiceStatus.OPEN) {
                throw new BusinessConflictException("A fatura de " + invoiceMonth + " está "
                        + existing.getStatus() + "; não é possível lançar nesta ocorrência.");
            }
            invoice = invoiceService.getOrCreate(account, invoiceMonth.getYear(), invoiceMonth.getMonthValue());
        }

        Transaction t = repository.save(Transaction.builder()
                .description(rule.getDescription())
                .amount(amount)
                .date(date)
                .type(rule.getType())
                .status(TransactionStatus.PENDING)
                .category(rule.getCategory())
                .account(account)
                .invoice(invoice)
                .recurrenceRule(rule)
                .recurrenceOccurrence(occurrence)
                .tenant(user.getTenant())
                .user(user)
                .build());
        return TransactionResponseDTO.fromEntity(t);
    }

    // Guard de idempotência da confirmação (a unique parcial no banco é a rede final).
    @Transactional(readOnly = true)
    public boolean existsMaterializedOccurrence(UUID ruleId, LocalDate occurrence) {
        return repository.existsByRecurrenceRuleIdAndRecurrenceOccurrence(ruleId, occurrence);
    }

    @Transactional
    public TransferResponseDTO createTransfer(TransferRequestDTO dto, User user) {
        if (dto.fromAccountId().equals(dto.toAccountId())) {
            throw new BusinessException("As contas de origem e destino devem ser diferentes.");
        }
        Account from = resolveAccount(dto.fromAccountId(), user);
        Account to   = resolveAccount(dto.toAccountId(), user);
        UUID transferId = UUID.randomUUID();
        String description = (dto.description() != null && !dto.description().isBlank())
                ? dto.description() : "Transferência";

        Transaction expense = repository.save(Transaction.builder()
                .description(description)
                .amount(dto.amount()).date(dto.date())
                .type(TransactionType.EXPENSE)
                .status(TransactionStatus.PAID)
                .installmentNumber(1).totalInstallments(1)
                .tenant(user.getTenant()).user(user)
                .account(from).transferId(transferId)
                .build());

        Transaction income = repository.save(Transaction.builder()
                .description(description)
                .amount(dto.amount()).date(dto.date())
                .type(TransactionType.INCOME)
                .status(TransactionStatus.PAID)
                .installmentNumber(1).totalInstallments(1)
                .tenant(user.getTenant()).user(user)
                .account(to).transferId(transferId)
                .build());

        return new TransferResponseDTO(
                transferId, expense.getId(), income.getId(),
                dto.amount(), dto.date(), description,
                from.getName(), to.getName());
    }

    @Transactional
    public void deleteTransfer(UUID transferId, User user) {
        List<Transaction> legs = repository.findByTransferIdAndTenant(transferId, user.getTenant());
        if (legs.isEmpty()) {
            throw new EntityNotFoundException("Transferência não encontrada.");
        }
        repository.deleteAll(legs);
    }

    @Transactional
    public TransactionResponseDTO update(UUID id, TransactionUpdateDTO dto, User user) {
        Transaction t = repository.findByIdAndTenant(id, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Transação não encontrada."));

        // #138: double-entry é invariante — as duas pernas nascem juntas (createTransfer) e
        // morrem juntas (deleteTransfer). Editar uma perna isolada cria/some dinheiro do tenant.
        if (t.getTransferId() != null) {
            throw new BusinessException(
                "Transação de transferência não pode ser editada individualmente. "
                + "Exclua a transferência (DELETE /api/transfers/{transferId}) e recrie.");
        }

        if (dto.description() != null) t.setDescription(dto.description());
        if (dto.amount() != null)      t.setAmount(dto.amount());
        if (dto.date() != null)        t.setDate(dto.date());
        if (dto.type() != null)        t.setType(dto.type());
        if (dto.status() != null)      t.setStatus(dto.status());
        if (dto.categoryId() != null)  t.setCategory(resolveCategory(dto.categoryId(), user));
        if (dto.accountId() != null)   t.setAccount(resolveAccount(dto.accountId(), user));

        List<String> propagate = dto.propagate();
        if (propagate != null && !propagate.isEmpty() && t.getInstallmentGroup() != null) {
            List<Transaction> futures = repository.findFuturePendingInGroup(
                    t.getInstallmentGroup(), t.getInstallmentNumber(), TransactionStatus.PENDING);
            for (Transaction future : futures) {
                if (propagate.contains("description") && dto.description() != null)
                    future.setDescription(dto.description());
                if (propagate.contains("amount") && dto.amount() != null)
                    future.setAmount(dto.amount());
                if (propagate.contains("categoryId") && dto.categoryId() != null)
                    future.setCategory(resolveCategory(dto.categoryId(), user));
                if (propagate.contains("accountId") && dto.accountId() != null)
                    future.setAccount(resolveAccount(dto.accountId(), user));
                if (propagate.contains("status") && dto.status() != null)
                    future.setStatus(dto.status());
            }
        }

        return TransactionResponseDTO.fromEntity(t);
    }

    @Transactional
    public DeleteInstallmentResultDTO delete(UUID id, DeleteInstallmentScope scope, User user) {
        Transaction t = repository.findByIdAndTenant(id, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Transação não encontrada."));

        // #138: excluir uma perna isolada deixa a irmã órfã e desbalanceia o double-entry.
        if (t.getTransferId() != null) {
            throw new BusinessException(
                "Perna de transferência não pode ser excluída individualmente. "
                + "Use DELETE /api/transfers/{transferId} para remover o par.");
        }

        // Incidente prod (2026-08-13): transação vinculada a um BudgetItem tem FK RESTRICT
        // (budget_items.transaction_id) — excluir sem desvincular estourava FK violation não
        // tratada (500). O vínculo é estado de negócio real (item REALIZED), então aqui o
        // certo é bloquear e pedir ação explícita do usuário, não desvincular silenciosamente.
        budgetItemRepository.findByTransaction(t).ifPresent(item -> {
            throw new BusinessException(
                "Transação vinculada a um item do planejamento mensal. "
                + "Desvincule o item antes de excluir a transação.");
        });

        if (scope == DeleteInstallmentScope.SINGLE || t.getInstallmentGroup() == null) {
            repository.delete(t);
            return new DeleteInstallmentResultDTO(1, 0);
        }

        InstallmentGroup group = t.getInstallmentGroup();
        List<Transaction> candidates = switch (scope) {
            case THIS_AND_NEXT -> repository
                    .findByInstallmentGroupAndInstallmentNumberGreaterThanEqualOrderByInstallmentNumberAsc(
                            group, t.getInstallmentNumber());
            case ALL -> repository.findByInstallmentGroupOrderByInstallmentNumberAsc(group);
            default -> List.of(t);
        };

        List<Transaction> toDelete = candidates.stream()
                .filter(tx -> tx.getStatus() != TransactionStatus.PAID)
                .toList();
        int skipped = candidates.size() - toDelete.size();

        repository.deleteAll(toDelete);
        return new DeleteInstallmentResultDTO(toDelete.size(), skipped);
    }

    // Compras até o fechamento ficam na fatura do mesmo mês; após o fechamento, vão para o próximo.
    private YearMonth resolveInvoiceMonth(LocalDate purchaseDate, int closingDay) {
        // Compras ATÉ o fechamento encerram a fatura do mês anterior.
        // Compras APÓS o fechamento iniciam a fatura do mês corrente.
        // Ex: closingDay=2 → compra em 03/06 → fatura de junho; compra em 02/07 → fatura de junho.
        return purchaseDate.getDayOfMonth() <= closingDay
                ? YearMonth.from(purchaseDate).minusMonths(1)
                : YearMonth.from(purchaseDate);
    }

    private Category resolveCategory(UUID categoryId, User user) {
        if (categoryId == null) return null;
        return categoryRepository.findByIdAndTenantIdAndDeletedAtIsNull(categoryId, user.getTenant().getId())
                .orElseThrow(() -> new EntityNotFoundException("Categoria não encontrada."));
    }

    private Account resolveAccount(UUID accountId, User user) {
        return accountRepository.findByIdAndTenant(accountId, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Conta não encontrada."));
    }
}
