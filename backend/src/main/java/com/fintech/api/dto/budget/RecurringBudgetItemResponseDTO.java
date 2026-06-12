package com.fintech.api.dto.budget;

import com.fintech.api.domain.budget.RecurringBudgetItem;
import com.fintech.api.domain.enums.TransactionType;
import java.math.BigDecimal;
import java.util.UUID;

public record RecurringBudgetItemResponseDTO(
    UUID id,
    String description,
    BigDecimal amount,
    TransactionType type,
    int dayOfMonth,
    UUID categoryId,
    String categoryName,
    UUID accountId,
    String accountName,
    boolean active
) {
    public static RecurringBudgetItemResponseDTO fromEntity(RecurringBudgetItem item) {
        return new RecurringBudgetItemResponseDTO(
            item.getId(),
            item.getDescription(),
            item.getAmount(),
            item.getType(),
            item.getDayOfMonth(),
            item.getCategory() != null ? item.getCategory().getId() : null,
            item.getCategory() != null ? item.getCategory().getName() : null,
            item.getAccount() != null ? item.getAccount().getId() : null,
            item.getAccount() != null ? item.getAccount().getName() : null,
            item.isActive()
        );
    }
}
