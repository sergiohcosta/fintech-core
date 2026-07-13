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
import com.fintech.api.domain.recurrence.RecurrenceRule;
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

import java.time.LocalDate;
import java.util.Optional;
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
        // #141: link agora passa pelos MESMOS guards de realize (antes pulava ciclo OPEN,
        // compatibilidade de tipo e sync de amount, e só bloqueava dupla-vinculação em OUTRO ciclo).
        requireOpenAndPending(item);
        // Escopado pelo tenant do item — sem isso, qualquer UUID de transação de OUTRO tenant
        // seria aceito (vazamento cross-tenant).
        Transaction tx = transactionRepository.findByIdAndTenant(transactionId, item.getTenant())
            .orElseThrow(() -> new EntityNotFoundException("Transação não encontrada."));
        attachExistingTransaction(item, tx);
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
        requireOpenAndPending(item);
        if (item.getSource() == BudgetItemSource.INSTALLMENT && transactionId == null) {
            throw new IllegalStateException("Parcelas de cartão devem ser realizadas vinculando a transação existente.");
        }

        Transaction tx;
        if (transactionId != null) {
            tx = transactionRepository.findByIdAndTenant(transactionId, tenant)
                .orElseThrow(() -> new AccessDeniedException("Acesso negado."));
        } else {
            // Sem transação informada: cria uma PAID a partir do item (realização "manual").
            tx = transactionRepository.save(Transaction.builder()
                .description(item.getDescription())
                .amount(item.getAmount())
                .date(item.getExpectedDate())
                .type(item.getType())
                .tenant(tenant)
                .user(user)
                .category(item.getCategory())
                .account(item.getAccount())
                .status(TransactionStatus.PAID)
                .build());
        }

        attachExistingTransaction(item, tx);
        return repository.save(item);
    }

    // #140: ao confirmar uma ocorrência de recorrência, vincula a transação materializada ao item
    // RECURRING PENDENTE do ciclo aberto (se houver). Sem isso a transação apareceria como avulsa e
    // o resumo do ciclo contaria o item planejado E a avulsa (dupla contagem). Orquestrado a partir
    // de RecurrenceRuleService.confirmOccurrence — TransactionService não conhece planejamento.
    // Best-effort: sem ciclo aberto ou sem item projetado correspondente, não faz nada.
    @Transactional
    public void linkRecurringOccurrence(Tenant tenant, RecurrenceRule rule, LocalDate occurrence, UUID transactionId) {
        Optional<BudgetItem> match = repository.findRecurringOccurrenceInOpenCycle(tenant, rule, occurrence);
        if (match.isEmpty()) return;
        Transaction tx = transactionRepository.findByIdAndTenant(transactionId, tenant)
            .orElseThrow(() -> new EntityNotFoundException("Transação não encontrada."));
        BudgetItem item = match.get();
        attachExistingTransaction(item, tx);
        repository.save(item);
    }

    // #141: guards compartilhados por link e realize. Antes só realize os aplicava; link os pulava,
    // permitindo dupla-vinculação no mesmo ciclo, tipo incompatível e amount dessincronizado —
    // dois caminhos de escrita para o mesmo estado com validações diferentes.
    private void requireOpenAndPending(BudgetItem item) {
        if (item.getCycle().getStatus() != BudgetCycleStatus.OPEN) {
            throw new IllegalStateException("O ciclo está fechado para alterações.");
        }
        if (item.getStatus() != BudgetItemStatus.PENDING) {
            throw new IllegalStateException("Apenas itens pendentes podem ser realizados.");
        }
    }

    private void attachExistingTransaction(BudgetItem item, Transaction tx) {
        // Uma transação pertence a no máximo UM item (qualquer ciclo) — impede dupla contagem.
        repository.findByTransaction(tx)
            .filter(existing -> !existing.getId().equals(item.getId()))
            .ifPresent(existing -> {
                throw new IllegalStateException("Esta transação já está vinculada a outro item.");
            });
        if (tx.getType() != item.getType()) {
            throw new IllegalStateException("O tipo da transação não é compatível com o tipo do item.");
        }
        item.setTransaction(tx);
        item.setAmount(tx.getAmount());
        item.setStatus(BudgetItemStatus.REALIZED);
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
