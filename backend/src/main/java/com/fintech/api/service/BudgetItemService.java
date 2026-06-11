package com.fintech.api.service;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetItemSource;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.BudgetItemCreateRequest;
import com.fintech.api.dto.budget.BudgetItemUpdateRequest;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.BudgetItemRepository;
import com.fintech.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BudgetItemService {

    private final BudgetItemRepository repository;
    private final TransactionRepository transactionRepository;

    @Transactional
    public BudgetItem create(BudgetCycle cycle, BudgetItemCreateRequest req, Tenant tenant, User user) {
        return repository.save(BudgetItem.builder()
            .cycle(cycle)
            .tenant(tenant)
            .description(req.description())
            .amount(req.amount())
            .type(req.type())
            .expectedDate(req.expectedDate())
            .source(BudgetItemSource.MANUAL)
            .createdBy(user)
            .build());
    }

    @Transactional
    public BudgetItem update(BudgetItem item, BudgetItemUpdateRequest req) {
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
        Transaction tx = transactionRepository.findById(transactionId)
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
    public void delete(BudgetItem item) {
        repository.delete(item);
    }

    @Transactional(readOnly = true)
    public BudgetItem findByIdAndTenant(UUID id, Tenant tenant) {
        return repository.findById(id)
            .filter(i -> i.getTenant().getId().equals(tenant.getId()))
            .orElseThrow(() -> new EntityNotFoundException("Item de planejamento não encontrado."));
    }
}
