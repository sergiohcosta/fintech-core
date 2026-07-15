package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.RecurrenceStatus;
import com.fintech.api.domain.recurrence.RecurrenceRule;
import com.fintech.api.domain.user.User;
import com.fintech.api.domain.recurrence.RecurrenceException;
import com.fintech.api.dto.recurrence.ConfirmOccurrenceDTO;
import com.fintech.api.dto.recurrence.RecurrenceRuleCreateDTO;
import com.fintech.api.dto.recurrence.RecurrenceRulePatchDTO;
import com.fintech.api.dto.recurrence.RecurrenceRuleResponseDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.exception.BusinessConflictException;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.RecurrenceExceptionRepository;
import com.fintech.api.repository.RecurrenceRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    private final RecurrenceExceptionRepository exceptionRepository;
    private final TransactionService transactionService;
    private final BudgetItemService budgetItemService;
    private final com.fintech.api.service.recurrence.RecurrenceProjectionService projectionService;

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

    @Transactional
    public RecurrenceRuleResponseDTO reactivate(UUID id, User user) {
        RecurrenceRule rule = findOwned(id, user);
        if (rule.getStatus() == RecurrenceStatus.ACTIVE) {
            throw new IllegalStateException("A regra já está ativa.");
        }
        rule.setStatus(RecurrenceStatus.ACTIVE);
        return RecurrenceRuleResponseDTO.fromEntity(repository.save(rule));
    }

    /**
     * Confirma (materializa) uma ocorrência fantasma como transação real. O override de
     * valor/data permite ajustar a conta que veio diferente neste mês (FR-02 parcial) — a
     * regra segue projetando o valor base nos meses seguintes (lê a regra, não um flag).
     */
    @Transactional
    public TransactionResponseDTO confirmOccurrence(
            UUID ruleId, LocalDate occurrence, ConfirmOccurrenceDTO body, User user) {
        RecurrenceRule rule = findOwned(ruleId, user);
        // #146: só é confirmável um slot REAL da regra ATIVA e não pulado. Sem isto, confirmar uma
        // data fora da expansão (ex: 14/07 numa regra BYMONTHDAY=15) convivia com o fantasma real
        // de 15/07 → pagamento 2× (a unique parcial não protege: datas diferentes).
        requireProjectableOccurrence(rule, occurrence);
        if (exceptionRepository.existsByRuleIdAndOccurrenceDate(ruleId, occurrence)) {
            throw new IllegalStateException("Esta ocorrência foi pulada (EXDATE) e não pode ser confirmada.");
        }
        // Guard explícito para mensagem amigável; a unique parcial (rule, occurrence) no banco
        // é a rede de segurança final contra corrida.
        if (transactionService.existsMaterializedOccurrence(ruleId, occurrence)) {
            throw new BusinessConflictException("Esta ocorrência já foi confirmada.");
        }
        BigDecimal amount = body != null ? body.amount() : null;
        LocalDate date = body != null ? body.date() : null;
        TransactionResponseDTO dto = transactionService.materializeFromRule(rule, occurrence, amount, date, user);
        // #140: vincula ao item RECURRING do ciclo aberto (best-effort) — evita dupla contagem.
        budgetItemService.linkRecurringOccurrence(user.getTenant(), rule, occurrence, dto.id());
        return dto;
    }

    /** Pula uma ocorrência: grava EXDATE. Idempotente — pular duas vezes não duplica linha. */
    @Transactional
    public void skipOccurrence(UUID ruleId, LocalDate occurrence, User user) {
        RecurrenceRule rule = findOwned(ruleId, user);
        // #146: pular só faz sentido para um slot real da regra ativa (pular um não-slot é ruído).
        requireProjectableOccurrence(rule, occurrence);
        if (!exceptionRepository.existsByRuleIdAndOccurrenceDate(ruleId, occurrence)) {
            exceptionRepository.save(RecurrenceException.builder()
                    .rule(rule).occurrenceDate(occurrence).build());
        }
    }

    // #146: regra precisa estar ATIVA e a data precisa ser um slot real da RRULE. IllegalStateException
    // é mapeado a 422 pelo GlobalExceptionHandler.
    private void requireProjectableOccurrence(RecurrenceRule rule, LocalDate occurrence) {
        if (rule.getStatus() != RecurrenceStatus.ACTIVE) {
            throw new IllegalStateException("A regra de recorrência está cancelada.");
        }
        if (!projectionService.occursOn(rule, occurrence)) {
            throw new IllegalStateException("A data informada não é uma ocorrência desta regra.");
        }
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
