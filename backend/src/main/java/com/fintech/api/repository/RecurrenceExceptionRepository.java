package com.fintech.api.repository;

import com.fintech.api.domain.recurrence.RecurrenceException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RecurrenceExceptionRepository extends JpaRepository<RecurrenceException, UUID> {
    // Batched — evita N+1 ao projetar várias regras na mesma janela.
    List<RecurrenceException> findByRuleIdInAndOccurrenceDateBetween(
            Collection<UUID> ruleIds, LocalDate from, LocalDate to);

    boolean existsByRuleIdAndOccurrenceDate(UUID ruleId, LocalDate occurrenceDate);
}
