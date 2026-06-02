package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.installment.InstallmentGroup;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.installment.DeleteInstallmentResultDTO;
import com.fintech.api.dto.installment.InstallmentGroupPatchDTO;
import com.fintech.api.dto.installment.InstallmentGroupResponseDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.InstallmentGroupRepository;
import com.fintech.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InstallmentGroupService {

    private final InstallmentGroupRepository groupRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public List<InstallmentGroupResponseDTO> findAll(User user) {
        return groupRepository.findByTenantOrderByCreatedAtDesc(user.getTenant())
                .stream()
                .map(g -> toDTO(g, transactionRepository
                        .findByInstallmentGroupOrderByInstallmentNumberAsc(g)))
                .toList();
    }

    @Transactional(readOnly = true)
    public InstallmentGroupResponseDTO findById(UUID id, User user) {
        InstallmentGroup group = resolveGroup(id, user);
        return toDTO(group, transactionRepository
                .findByInstallmentGroupOrderByInstallmentNumberAsc(group));
    }

    @Transactional
    public DeleteInstallmentResultDTO deleteGroup(UUID id, User user) {
        InstallmentGroup group = resolveGroup(id, user);
        List<Transaction> all = transactionRepository
                .findByInstallmentGroupOrderByInstallmentNumberAsc(group);
        List<Transaction> toDelete = all.stream()
                .filter(t -> t.getStatus() != TransactionStatus.PAID)
                .toList();
        transactionRepository.deleteAll(toDelete);
        return new DeleteInstallmentResultDTO(toDelete.size(), all.size() - toDelete.size());
    }

    @Transactional
    public InstallmentGroupResponseDTO patch(UUID id, InstallmentGroupPatchDTO dto, User user) {
        InstallmentGroup group = resolveGroup(id, user);
        List<Transaction> pending = transactionRepository
                .findByInstallmentGroupOrderByInstallmentNumberAsc(group)
                .stream()
                .filter(t -> t.getStatus() != TransactionStatus.PAID)
                .toList();
        List<String> fields = dto.fields() != null ? dto.fields() : List.of();

        if (fields.contains("description") && dto.description() != null) {
            group.setDescription(dto.description());
            pending.forEach(t -> t.setDescription(dto.description()));
        }
        if (fields.contains("categoryId")) {
            Category cat = dto.categoryId() != null ? resolveCategory(dto.categoryId(), user) : null;
            group.setCategory(cat);
            pending.forEach(t -> t.setCategory(cat));
        }
        if (fields.contains("accountId") && dto.accountId() != null) {
            Account acc = resolveAccount(dto.accountId(), user);
            group.setAccount(acc);
            pending.forEach(t -> t.setAccount(acc));
        }
        if (fields.contains("installmentAmount") && dto.installmentAmount() != null) {
            pending.forEach(t -> t.setAmount(dto.installmentAmount()));
        }
        if (fields.contains("status") && dto.status() != null) {
            pending.forEach(t -> t.setStatus(dto.status()));
        }

        return toDTO(group, transactionRepository
                .findByInstallmentGroupOrderByInstallmentNumberAsc(group));
    }

    private InstallmentGroup resolveGroup(UUID id, User user) {
        return groupRepository.findByIdAndTenant(id, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Grupo de parcelamento não encontrado."));
    }

    private Category resolveCategory(UUID categoryId, User user) {
        return categoryRepository.findByIdAndTenantIdAndDeletedAtIsNull(categoryId, user.getTenant().getId())
                .orElseThrow(() -> new EntityNotFoundException("Categoria não encontrada."));
    }

    private Account resolveAccount(UUID accountId, User user) {
        return accountRepository.findByIdAndTenant(accountId, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Conta não encontrada."));
    }

    private InstallmentGroupResponseDTO toDTO(InstallmentGroup group, List<Transaction> txs) {
        long paidCount    = txs.stream().filter(t -> t.getStatus() == TransactionStatus.PAID).count();
        long pendingCount = txs.stream().filter(t -> t.getStatus() == TransactionStatus.PENDING).count();
        LocalDate nextDue = txs.stream()
                .filter(t -> t.getStatus() == TransactionStatus.PENDING)
                .map(Transaction::getDate)
                .min(LocalDate::compareTo)
                .orElse(null);
        BigDecimal installmentAmt = txs.isEmpty() ? BigDecimal.ZERO : txs.get(0).getAmount();

        return new InstallmentGroupResponseDTO(
                group.getId(),
                group.getDescription(),
                group.getTotalAmount(),
                installmentAmt,
                group.getTotalInstallments(),
                paidCount,
                pendingCount,
                nextDue,
                group.getCategory() != null ? group.getCategory().getName() : null,
                group.getCategory() != null ? group.getCategory().getId() : null,
                group.getAccount() != null ? group.getAccount().getName() : null,
                group.getAccount() != null ? group.getAccount().getId() : null,
                txs.stream().map(TransactionResponseDTO::fromEntity).toList()
        );
    }
}
