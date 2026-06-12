package com.fintech.api.dto.budget;

import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetItemSource;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.enums.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BudgetItemResponseDTO(
    UUID id,
    String description,
    BigDecimal amount,
    TransactionType type,
    LocalDate expectedDate,
    BudgetItemSource source,
    BudgetItemStatus status,
    UUID categoryId,
    String categoryName,
    UUID accountId,
    String accountName,
    UUID transactionId,
    UUID installmentGroupId
) {
    public static BudgetItemResponseDTO fromEntity(BudgetItem item) {
        return new BudgetItemResponseDTO(
            item.getId(),
            item.getDescription(),
            item.getAmount(),
            item.getType(),
            item.getExpectedDate(),
            item.getSource(),
            item.getStatus(),
            item.getCategory() != null ? item.getCategory().getId() : null,
            item.getCategory() != null ? item.getCategory().getName() : null,
            item.getAccount() != null ? item.getAccount().getId() : null,
            item.getAccount() != null ? item.getAccount().getName() : null,
            item.getTransaction() != null ? item.getTransaction().getId() : null,
            item.getInstallmentGroup() != null ? item.getInstallmentGroup().getId() : null
        );
    }
}
