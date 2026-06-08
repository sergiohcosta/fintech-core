package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.enums.DeleteInstallmentScope;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.installment.InstallmentGroup;
import com.fintech.api.domain.invoice.Invoice;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.installment.DeleteInstallmentResultDTO;
import com.fintech.api.dto.transaction.TransactionRequestDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.dto.transaction.TransactionUpdateDTO;
import com.fintech.api.dto.transfer.TransferRequestDTO;
import com.fintech.api.dto.transfer.TransferResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
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

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository repository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;
    private final InstallmentGroupRepository installmentGroupRepository;
    private final CreditCardDetailsRepository creditCardDetailsRepository;
    private final InvoiceService invoiceService;

    @Transactional(readOnly = true)
    public List<TransactionResponseDTO> findAll(User user, UUID invoiceId, List<UUID> accountIds,
            TransactionStatus status, TransactionType type, LocalDate startDate, LocalDate endDate) {
        if ((startDate == null) != (endDate == null)) {
            throw new IllegalArgumentException("startDate e endDate devem ser informados juntos ou omitidos juntos");
        }
        if (invoiceId != null) {
            Invoice invoice = invoiceService.findByIdAndTenant(invoiceId, user.getTenant());
            return repository.findAllByTenantAndInvoiceWithDetails(user.getTenant(), invoice)
                    .stream().map(TransactionResponseDTO::fromEntity).toList();
        }
        // Sentinelas substituem null para evitar IS NULL no JPQL com LocalDate.
        // PostgreSQL não consegue inferir o tipo de "? IS NULL" sem contexto de coluna.
        LocalDate effectiveStart = startDate != null ? startDate : LocalDate.of(1000, 1, 1);
        LocalDate effectiveEnd   = endDate   != null ? endDate   : LocalDate.of(9999, 12, 31);

        // Lista vazia ou null = sem filtro de conta (accountIdCount = 0 → condição ignorada no JPQL).
        // Lista com itens = filtra pelas contas informadas (accountIdCount > 0 → IN ativado).
        List<UUID> effectiveAccountIds = (accountIds == null || accountIds.isEmpty()) ? List.of() : accountIds;
        int accountIdCount = effectiveAccountIds.size();

        return repository.findAllByTenantWithFilters(
                        user.getTenant(), effectiveAccountIds, accountIdCount, status, type, effectiveStart, effectiveEnd)
                .stream()
                .sorted(Comparator.comparing(this::effectiveSortDate, Comparator.reverseOrder()))
                .map(TransactionResponseDTO::fromEntity)
                .toList();
    }

    // Para parcelas de cartão de crédito (installmentGroup presente), a posição na linha
    // do tempo é o dueDate da fatura — alinhado com a regra JPQL de filtro de período.
    // Transações avulsas de cartão (sem installmentGroup) usam t.date: a data de compra
    // é única e é a informação relevante, assim como qualquer conta regular.
    private LocalDate effectiveSortDate(Transaction t) {
        if (t.getInstallmentGroup() != null && t.getInvoice() != null) {
            return t.getInvoice().getDueDate();
        }
        return t.getDate();
    }

    @Transactional(readOnly = true)
    public TransactionResponseDTO findById(UUID id, User user) {
        return TransactionResponseDTO.fromEntity(
                repository.findByIdAndTenant(id, user.getTenant())
                        .orElseThrow(() -> new EntityNotFoundException("Transação não encontrada.")));
    }

    @Transactional
    public List<TransactionResponseDTO> create(TransactionRequestDTO dto, User user) {
        Category category = resolveCategory(dto.categoryId(), user);
        Account account = resolveAccount(dto.accountId(), user);

        int installments = (dto.totalInstallments() != null && dto.totalInstallments() > 1)
                ? dto.totalInstallments() : 1;
        BigDecimal installmentAmount = dto.amount()
                .divide(BigDecimal.valueOf(installments), 2, RoundingMode.HALF_EVEN);

        boolean isCreditCard = AccountType.CREDIT_CARD.equals(account.getType());
        int closingDay = 0;
        if (isCreditCard) {
            closingDay = creditCardDetailsRepository.findByAccount(account)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Detalhes do cartão não encontrados para a conta."))
                    .getClosingDay();
        }
        final int finalClosingDay = closingDay;

        InstallmentGroup group = null;
        if (installments > 1) {
            group = installmentGroupRepository.save(InstallmentGroup.builder()
                    .description(dto.description())
                    .totalAmount(dto.amount())
                    .totalInstallments(installments)
                    .account(account)
                    .category(category)
                    .tenant(user.getTenant())
                    .build());
        }

        final InstallmentGroup finalGroup = group;
        List<Transaction> created = new ArrayList<>();
        for (int i = 0; i < installments; i++) {
            Invoice invoice = null;
            LocalDate transactionDate;

            if (isCreditCard) {
                YearMonth invoiceMonth = resolveInvoiceMonth(dto.date(), finalClosingDay).plusMonths(i);
                invoice = invoiceService.getOrCreate(account, invoiceMonth.getYear(), invoiceMonth.getMonthValue());
                transactionDate = dto.date(); // data de compra igual em todas as parcelas
            } else {
                transactionDate = dto.date().plusMonths(i);
            }

            created.add(repository.save(Transaction.builder()
                    .description(dto.description())
                    .amount(installmentAmount)
                    .date(transactionDate)
                    .type(dto.type())
                    .status(dto.status() != null ? dto.status() : TransactionStatus.PENDING)
                    .installmentNumber(i + 1)
                    .totalInstallments(installments)
                    .installmentGroup(finalGroup)
                    .invoice(invoice)
                    .tenant(user.getTenant())
                    .user(user)
                    .category(category)
                    .account(account)
                    .build()));
        }
        return created.stream().map(TransactionResponseDTO::fromEntity).toList();
    }

    @Transactional
    public TransferResponseDTO createTransfer(TransferRequestDTO dto, User user) {
        if (dto.fromAccountId().equals(dto.toAccountId())) {
            throw new IllegalArgumentException("As contas de origem e destino devem ser diferentes.");
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
        return purchaseDate.getDayOfMonth() <= closingDay
                ? YearMonth.from(purchaseDate)
                : YearMonth.from(purchaseDate).plusMonths(1);
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
