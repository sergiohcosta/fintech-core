package com.fintech.api.service.recurrence;

import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.RecurrenceStatus;
import com.fintech.api.domain.recurrence.RecurrenceRule;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.repository.RecurrenceExceptionRepository;
import com.fintech.api.repository.RecurrenceRuleRepository;
import com.fintech.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Projeta as "linhas fantasma" de uma janela, on-the-fly. Nada é persistido.
 *
 * <p>fantasma(janela) = expand(rrule) − {ocorrências já materializadas} − {EXDATE}.
 * Tudo keyed pela data da ocorrência. Custo: 3 queries indexadas por tenant + expansão
 * em memória limitada à janela (exceções são esparsas).
 */
@Service
@RequiredArgsConstructor
public class RecurrenceProjectionService {

    private final RecurrenceRuleRepository ruleRepository;
    private final RecurrenceExceptionRepository exceptionRepository;
    private final TransactionRepository transactionRepository;
    private final RecurrenceExpander expander;

    @Transactional(readOnly = true)
    public List<ProjectedOccurrence> project(Tenant tenant, LocalDate from, LocalDate to) {
        List<RecurrenceRule> rules = ruleRepository.findByTenantAndStatus(tenant, RecurrenceStatus.ACTIVE);
        if (rules.isEmpty()) return List.of();

        List<UUID> ruleIds = rules.stream().map(RecurrenceRule::getId).toList();

        // Ocorrências já resolvidas, batched (sem N+1):
        Set<DatedRule> materialized = transactionRepository
                .findByRecurrenceRuleIdInAndRecurrenceOccurrenceBetween(ruleIds, from, to).stream()
                .map(t -> new DatedRule(t.getRecurrenceRule().getId(), t.getRecurrenceOccurrence()))
                .collect(Collectors.toSet());
        Set<DatedRule> skipped = exceptionRepository
                .findByRuleIdInAndOccurrenceDateBetween(ruleIds, from, to).stream()
                .map(e -> new DatedRule(e.getRule().getId(), e.getOccurrenceDate()))
                .collect(Collectors.toSet());

        List<ProjectedOccurrence> ghosts = new ArrayList<>();
        for (RecurrenceRule rule : rules) {
            for (LocalDate date : expander.expand(rule.getRrule(), rule.getStartDate(), from, to)) {
                DatedRule key = new DatedRule(rule.getId(), date);
                if (materialized.contains(key) || skipped.contains(key)) continue;
                ghosts.add(toOccurrence(rule, date));
            }
        }
        return ghosts;
    }

    // #146: uma data só é confirmável/pulável se pertence à expansão da RRULE. Janela = o mês da
    // data (a expansão sempre recebe janela; nunca "infinito"). Antes de startDate a regra não ocorre.
    public boolean occursOn(RecurrenceRule rule, LocalDate date) {
        if (date.isBefore(rule.getStartDate())) return false;
        LocalDate monthStart = date.withDayOfMonth(1);
        LocalDate monthEnd = date.withDayOfMonth(date.lengthOfMonth());
        return expander.expand(rule.getRrule(), rule.getStartDate(), monthStart, monthEnd).contains(date);
    }

    private ProjectedOccurrence toOccurrence(RecurrenceRule rule, LocalDate date) {
        Category cat = rule.getCategory();
        return new ProjectedOccurrence(
                rule.getId(), date, rule.getDescription(), rule.getBaseAmount(), rule.getType(),
                cat != null ? cat.getId() : null,
                cat != null ? cat.getName() : null,
                cat != null ? cat.getIcon() : null,
                rule.getAccount().getId(), rule.getAccount().getName());
    }

    // Chave (regra, data) para subtração O(1).
    private record DatedRule(UUID ruleId, LocalDate date) {}
}
