package com.fintech.api.service;

import com.fintech.api.domain.budget.RecurringBudgetItem;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.RecurringBudgetItemRequest;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.RecurringBudgetItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RecurringBudgetItemService {

    private final RecurringBudgetItemRepository repository;

    @Transactional(readOnly = true)
    public List<RecurringBudgetItem> listActive(Tenant tenant) {
        return repository.findAllByTenantAndActiveTrueOrderByDayOfMonthAscDescriptionAsc(tenant);
    }

    @Transactional
    public RecurringBudgetItem create(RecurringBudgetItemRequest req, Tenant tenant, User user) {
        return repository.save(RecurringBudgetItem.builder()
            .tenant(tenant)
            .description(req.description())
            .amount(req.amount())
            .type(req.type())
            .dayOfMonth(req.dayOfMonth())
            .createdBy(user)
            .build());
    }

    @Transactional
    public RecurringBudgetItem update(UUID id, RecurringBudgetItemRequest req, Tenant tenant) {
        RecurringBudgetItem item = findByIdAndTenant(id, tenant);
        item.setDescription(req.description());
        item.setAmount(req.amount());
        item.setType(req.type());
        item.setDayOfMonth(req.dayOfMonth());
        return repository.save(item);
    }

    @Transactional
    public void deactivate(UUID id, Tenant tenant) {
        RecurringBudgetItem item = findByIdAndTenant(id, tenant);
        item.setActive(false);
        repository.save(item);
    }

    @Transactional(readOnly = true)
    public RecurringBudgetItem findByIdAndTenant(UUID id, Tenant tenant) {
        return repository.findByIdAndTenant(id, tenant)
            .orElseThrow(() -> new EntityNotFoundException("Template recorrente não encontrado."));
    }
}
