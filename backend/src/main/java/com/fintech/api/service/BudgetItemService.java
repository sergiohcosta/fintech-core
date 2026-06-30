package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.exception.BusinessException;
import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.enums.BudgetItemSource;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.BudgetItemCreateRequest;
import com.fintech.api.dto.budget.BudgetItemUpdateRequest;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.BudgetItemRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.TransactionRepository;
import org.springframework.security.access.AccessDeniedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BudgetItemService {

    private final BudgetItemRepository repository;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;

    @Transactional
    public BudgetItem create(BudgetCycle cycle, BudgetItemCreateRequest req, Tenant tenant, User user) {
        if (cycle.getStatus() != BudgetCycleStatus.OPEN) {
            throw new IllegalStateException("O ciclo está fechado para alterações.");
        }
        if (req.expectedDate().isBefore(cycle.getStartDate()) || req.expectedDate().isAfter(cycle.getEndDate())) {
            throw new BusinessException("Data deve estar dentro do período do ciclo.");
        }

        Category category = null;
        if (req.categoryId() != null) {
            category = categoryRepository.findByIdAndTenantIdAndDeletedAtIsNull(req.categoryId(), tenant.getId())
                .orElseThrow(() -> new BusinessException("Categoria não encontrada ou não pertence ao tenant."));
        }

        Account account = null;
        if (req.accountId() != null) {
            account = accountRepository.findByIdAndTenant(req.accountId(), tenant)
                .orElseThrow(() -> new BusinessException("Conta não encontrada ou não pertence ao tenant."));
        }

        return repository.save(BudgetItem.builder()
            .cycle(cycle)
            .tenant(tenant)
            .description(req.description())
            .amount(req.amount())
            .type(req.type())
            .expectedDate(req.expectedDate())
            .category(category)
            .account(account)
            .source(BudgetItemSource.MANUAL)
            .createdBy(user)
            .build());
    }

    @Transactional
    public BudgetItem update(BudgetItem item, BudgetItemUpdateRequest req) {
        if (item.getCycle().getStatus() != BudgetCycleStatus.OPEN) {
            throw new IllegalStateException("O ciclo está fechado para alterações.");
        }
        if (item.getStatus() == BudgetItemStatus.REALIZED) {
            throw new IllegalStateException("Itens realizados são imutáveis.");
        }
        if (item.getStatus() == BudgetItemStatus.SKIPPED) {
            throw new IllegalStateException("O item deve ser revertido para PENDING antes de ser editado.");
        }
        if (item.getSource() == BudgetItemSource.INSTALLMENT) {
            throw new IllegalStateException("Itens de parcela não podem ser editados manualmente.");
        }
        item.setDescription(req.description());
        item.setAmount(req.amount());
        item.setExpectedDate(req.expectedDate());
        return repository.save(item);
    }

    @Transactional
    public BudgetItem link(BudgetItem item, UUID transactionId) {
        // Escopado pelo tenant do item (já validado em findByIdAndTenant no controller) — sem isso,
        // qualquer UUID de transação de OUTRO tenant seria aceito (vazamento cross-tenant).
        Transaction tx = transactionRepository.findByIdAndTenant(transactionId, item.getTenant())
            .orElseThrow(() -> new EntityNotFoundException("Transação não encontrada."));

        if (repository.findByTransactionAndCycleNot(tx, item.getCycle()).isPresent()) {
            throw new IllegalStateException("Esta transação já está vinculada a outro item do plano.");
        }

        item.setTransaction(tx);
        item.setStatus(BudgetItemStatus.REALIZED);
        return repository.save(item);
    }

    @Transactional
    public BudgetItem unlink(BudgetItem item) {
        item.setTransaction(null);
        item.setStatus(BudgetItemStatus.PENDING);
        return repository.save(item);
    }

    @Transactional
    public BudgetItem realize(BudgetItem item, UUID transactionId, Tenant tenant, User user) {
        if (item.getCycle().getStatus() != BudgetCycleStatus.OPEN) {
            throw new IllegalStateException("O ciclo está fechado para alterações.");
        }
        if (item.getStatus() != BudgetItemStatus.PENDING) {
            throw new IllegalStateException("Apenas itens pendentes podem ser realizados.");
        }
        if (item.getSource() == BudgetItemSource.INSTALLMENT && transactionId == null) {
            throw new IllegalStateException("Parcelas de cartão devem ser realizadas vinculando a transação existente.");
        }

        Transaction tx;
        if (transactionId != null) {
            tx = transactionRepository.findByIdAndTenant(transactionId, tenant)
                .orElseThrow(() -> new AccessDeniedException("Acesso negado."));

            repository.findByTransaction(tx)
                .filter(existing -> !existing.getId().equals(item.getId()))
                .ifPresent(existing -> {
                    throw new IllegalStateException("Esta transação já está vinculada a outro item.");
                });

            if (tx.getType() != item.getType()) {
                throw new IllegalStateException("O tipo da transação não é compatível com o tipo do item.");
            }
        } else {
            tx = Transaction.builder()
                .description(item.getDescription())
                .amount(item.getAmount())
                .date(item.getExpectedDate())
                .type(item.getType())
                .tenant(tenant)
                .user(user)
                .category(item.getCategory())
                .account(item.getAccount())
                .status(TransactionStatus.PAID)
                .build();
            tx = transactionRepository.save(tx);
        }

        item.setTransaction(tx);
        item.setAmount(tx.getAmount());
        item.setStatus(BudgetItemStatus.REALIZED);
        return repository.save(item);
    }

    @Transactional
    public BudgetItem unrealize(BudgetItem item) {
        if (item.getCycle().getStatus() != BudgetCycleStatus.OPEN) {
            throw new IllegalStateException("O ciclo está fechado para alterações.");
        }
        if (item.getStatus() != BudgetItemStatus.REALIZED) {
            throw new IllegalStateException("Apenas itens realizados podem ser desvinculados.");
        }
        item.setTransaction(null);
        item.setStatus(BudgetItemStatus.PENDING);
        return repository.save(item);
    }

    @Transactional
    public BudgetItem skip(BudgetItem item) {
        if (item.getCycle().getStatus() != BudgetCycleStatus.OPEN) {
            throw new IllegalStateException("O ciclo está fechado para alterações.");
        }
        if (item.getStatus() == BudgetItemStatus.REALIZED) {
            throw new IllegalStateException("Itens realizados não podem ser pulados.");
        }
        if (item.getStatus() != BudgetItemStatus.PENDING) {
            throw new IllegalStateException("Apenas itens pendentes podem ser pulados.");
        }
        item.setStatus(BudgetItemStatus.SKIPPED);
        return repository.save(item);
    }

    @Transactional
    public BudgetItem unskip(BudgetItem item) {
        if (item.getCycle().getStatus() != BudgetCycleStatus.OPEN) {
            throw new IllegalStateException("O ciclo está fechado para alterações.");
        }
        if (item.getStatus() != BudgetItemStatus.SKIPPED) {
            throw new IllegalStateException("Apenas itens pulados podem ser revertidos.");
        }
        item.setStatus(BudgetItemStatus.PENDING);
        return repository.save(item);
    }

    @Transactional
    public void delete(BudgetItem item) {
        if (item.getCycle().getStatus() != BudgetCycleStatus.OPEN) {
            throw new IllegalStateException("O ciclo está fechado para alterações.");
        }
        if (item.getStatus() == BudgetItemStatus.REALIZED) {
            throw new IllegalStateException("Itens realizados são imutáveis.");
        }
        repository.delete(item);
    }

    @Transactional(readOnly = true)
    public BudgetItem findByIdAndTenant(UUID id, Tenant tenant) {
        return repository.findById(id)
            .filter(i -> i.getTenant().getId().equals(tenant.getId()))
            .orElseThrow(() -> new AccessDeniedException("Acesso negado."));
    }
}
