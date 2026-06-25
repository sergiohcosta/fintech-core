package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.budget.RecurringBudgetItem;
import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.RecurringBudgetItemRequest;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.RecurringBudgetItemRepository;
import org.springframework.security.access.AccessDeniedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RecurringBudgetItemService {

    private final RecurringBudgetItemRepository repository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public List<RecurringBudgetItem> listByTenant(Tenant tenant, Boolean activeFilter) {
        if (activeFilter == null) {
            return repository.findAllByTenantOrderByDescriptionAsc(tenant);
        }
        return repository.findAllByTenantAndActiveOrderByDescriptionAsc(tenant, activeFilter);
    }

    @Transactional
    public RecurringBudgetItem create(RecurringBudgetItemRequest req, Tenant tenant, User user) {
        Category category = resolveCategory(req.categoryId(), tenant);
        Account account = resolveAccount(req.accountId(), tenant);

        return repository.save(RecurringBudgetItem.builder()
            .tenant(tenant)
            .description(req.description())
            .amount(req.amount())
            .type(req.type())
            .dayOfMonth(req.dayOfMonth())
            .category(category)
            .account(account)
            .createdBy(user)
            .build());
    }

    @Transactional
    public RecurringBudgetItem update(UUID id, RecurringBudgetItemRequest req, Tenant tenant) {
        RecurringBudgetItem item = loadByIdAndTenant(id, tenant);

        if (!item.isActive()) {
            throw new IllegalStateException("O item deve ser reativado antes de ser editado.");
        }

        Category category = resolveCategory(req.categoryId(), tenant);
        Account account = resolveAccount(req.accountId(), tenant);

        item.setDescription(req.description());
        item.setAmount(req.amount());
        item.setType(req.type());
        item.setDayOfMonth(req.dayOfMonth());
        item.setCategory(category);
        item.setAccount(account);
        return repository.save(item);
    }

    @Transactional
    public RecurringBudgetItem reactivate(UUID id, Tenant tenant) {
        RecurringBudgetItem item = loadByIdAndTenant(id, tenant);
        item.setActive(true);
        return repository.save(item);
    }

    @Transactional
    public void deactivate(UUID id, Tenant tenant) {
        RecurringBudgetItem item = loadByIdAndTenant(id, tenant);
        item.setActive(false);
        repository.save(item);
    }

    /**
     * Lookup interno SEM @Transactional. Usado apenas por update/reactivate/deactivate
     * (já @Transactional read-write) — o find participa da transação do chamador. Antes era
     * um método @Transactional público chamado via 'this', o que passava por fora do proxy
     * do Spring e tornava a anotação inócua (regra S6809). Como não há chamador externo
     * (controller usa update/reactivate/deactivate diretamente), virou privado.
     */
    private RecurringBudgetItem loadByIdAndTenant(UUID id, Tenant tenant) {
        return repository.findByIdAndTenant(id, tenant)
            .orElseThrow(() -> new AccessDeniedException("Acesso negado."));
    }

    private Category resolveCategory(UUID categoryId, Tenant tenant) {
        if (categoryId == null) {
            return null;
        }
        return categoryRepository.findByIdAndTenantIdAndDeletedAtIsNull(categoryId, tenant.getId())
            .orElseThrow(() -> new EntityNotFoundException("Categoria não encontrada ou não pertence ao tenant."));
    }

    private Account resolveAccount(UUID accountId, Tenant tenant) {
        if (accountId == null) {
            return null;
        }
        return accountRepository.findByIdAndTenant(accountId, tenant)
            .orElseThrow(() -> new EntityNotFoundException("Conta não encontrada ou não pertence ao tenant."));
    }
}
