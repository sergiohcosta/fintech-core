package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.RecurrenceStatus;
import com.fintech.api.domain.recurrence.RecurrenceRule;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.recurrence.RecurrenceRuleCreateDTO;
import com.fintech.api.dto.recurrence.RecurrenceRulePatchDTO;
import com.fintech.api.dto.recurrence.RecurrenceRuleResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.RecurrenceRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * CRUD das regras de recorrência — sempre escopado pelo tenant do usuário autenticado.
 * "Cancelar" é soft-delete (status CANCELLED), preservando o histórico de transações já
 * materializadas a partir da regra.
 */
@Service
@RequiredArgsConstructor
public class RecurrenceRuleService {

    private final RecurrenceRuleRepository repository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;

    @Transactional
    public RecurrenceRuleResponseDTO create(RecurrenceRuleCreateDTO dto, User user) {
        Account account = resolveAccount(dto.accountId(), user);
        Category category = resolveCategory(dto.categoryId(), user);
        RecurrenceRule rule = repository.save(RecurrenceRule.builder()
                .tenant(user.getTenant())
                .description(dto.description())
                .baseAmount(dto.baseAmount())
                .type(dto.type())
                .category(category)
                .account(account)
                .rrule(dto.rrule())
                .startDate(dto.startDate())
                .status(RecurrenceStatus.ACTIVE)
                .createdBy(user)
                .build());
        return RecurrenceRuleResponseDTO.fromEntity(rule);
    }

    @Transactional(readOnly = true)
    public List<RecurrenceRuleResponseDTO> findAll(User user) {
        return repository.findByTenantAndStatus(user.getTenant(), RecurrenceStatus.ACTIVE)
                .stream().map(RecurrenceRuleResponseDTO::fromEntity).toList();
    }

    @Transactional(readOnly = true)
    public RecurrenceRuleResponseDTO findById(UUID id, User user) {
        return RecurrenceRuleResponseDTO.fromEntity(findOwned(id, user));
    }

    @Transactional
    public RecurrenceRuleResponseDTO patch(UUID id, RecurrenceRulePatchDTO dto, User user) {
        RecurrenceRule rule = findOwned(id, user);
        if (dto.description() != null) rule.setDescription(dto.description());
        if (dto.baseAmount() != null)  rule.setBaseAmount(dto.baseAmount());
        return RecurrenceRuleResponseDTO.fromEntity(rule);
    }

    @Transactional
    public void cancel(UUID id, User user) {
        findOwned(id, user).setStatus(RecurrenceStatus.CANCELLED);
    }

    // Helper de propriedade: 404 se a regra não existe OU é de outro tenant (sem vazamento).
    RecurrenceRule findOwned(UUID id, User user) {
        return repository.findByIdAndTenant(id, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Regra de recorrência não encontrada."));
    }

    private Account resolveAccount(UUID accountId, User user) {
        return accountRepository.findByIdAndTenant(accountId, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Conta não encontrada."));
    }

    private Category resolveCategory(UUID categoryId, User user) {
        if (categoryId == null) return null;
        return categoryRepository.findByIdAndTenantIdAndDeletedAtIsNull(categoryId, user.getTenant().getId())
                .orElseThrow(() -> new EntityNotFoundException("Categoria não encontrada."));
    }
}
