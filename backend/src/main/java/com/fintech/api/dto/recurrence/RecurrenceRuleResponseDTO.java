package com.fintech.api.dto.recurrence;

import com.fintech.api.domain.enums.RecurrenceStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.recurrence.RecurrenceRule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RecurrenceRuleResponseDTO(
        UUID id, String description, BigDecimal baseAmount, TransactionType type,
        UUID categoryId, String categoryName, UUID accountId, String accountName,
        String rrule, LocalDate startDate, RecurrenceStatus status) {

    public static RecurrenceRuleResponseDTO fromEntity(RecurrenceRule r) {
        var cat = r.getCategory();
        return new RecurrenceRuleResponseDTO(
                r.getId(), r.getDescription(), r.getBaseAmount(), r.getType(),
                cat != null ? cat.getId() : null, cat != null ? cat.getName() : null,
                r.getAccount().getId(), r.getAccount().getName(),
                r.getRrule(), r.getStartDate(), r.getStatus());
    }
}
