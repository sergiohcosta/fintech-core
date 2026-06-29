package com.fintech.api.service.recurrence;

import com.fintech.api.domain.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Uma "linha fantasma" projetada — nunca persistida. */
public record ProjectedOccurrence(
        UUID ruleId,
        LocalDate occurrenceDate,
        String description,
        BigDecimal amount,
        TransactionType type,
        UUID categoryId,
        String categoryName,
        String categoryIcon,
        UUID accountId,
        String accountName) {
}
