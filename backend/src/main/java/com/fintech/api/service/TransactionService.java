package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.transaction.TransactionRequestDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.dto.transaction.TransactionUpdateDTO;
import com.fintech.api.dto.transfer.TransferRequestDTO;
import com.fintech.api.dto.transfer.TransferResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository repository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public List<TransactionResponseDTO> findAll(User user) {
        return repository.findAllByTenantOrderByDateDesc(user.getTenant())
                .stream()
                .map(TransactionResponseDTO::fromEntity)
                .toList();
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

        int installments = (dto.totalInstallments() != null && dto.totalInstallments() > 0)
                ? dto.totalInstallments() : 1;
        BigDecimal installmentAmount = dto.amount().divide(BigDecimal.valueOf(installments), 2, RoundingMode.HALF_EVEN);

        List<Transaction> created = new ArrayList<>();
        for (int i = 0; i < installments; i++) {
            created.add(repository.save(Transaction.builder()
                    .description(dto.description())
                    .amount(installmentAmount)
                    .date(dto.date().plusMonths(i))
                    .type(dto.type())
                    .status(dto.status() != null ? dto.status() : TransactionStatus.PENDING)
                    .installmentNumber(i + 1)
                    .totalInstallments(installments)
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
                transferId,
                expense.getId(),
                income.getId(),
                dto.amount(),
                dto.date(),
                description,
                from.getName(),
                to.getName()
        );
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

        if (dto.categoryId() != null) {
            t.setCategory(resolveCategory(dto.categoryId(), user));
        }
        if (dto.accountId() != null) {
            t.setAccount(resolveAccount(dto.accountId(), user));
        }
        return TransactionResponseDTO.fromEntity(t);
    }

    @Transactional
    public void delete(UUID id, User user) {
        repository.delete(
                repository.findByIdAndTenant(id, user.getTenant())
                        .orElseThrow(() -> new EntityNotFoundException("Transação não encontrada.")));
    }

    private Category resolveCategory(UUID categoryId, User user) {
        if (categoryId == null) return null;
        return categoryRepository.findByIdAndTenantId(categoryId, user.getTenant().getId())
                .orElseThrow(() -> new EntityNotFoundException("Categoria não encontrada."));
    }

    private Account resolveAccount(UUID accountId, User user) {
        return accountRepository.findByIdAndTenant(accountId, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Conta não encontrada."));
    }
}
