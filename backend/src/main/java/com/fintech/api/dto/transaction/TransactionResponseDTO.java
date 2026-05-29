package com.fintech.api.dto.transaction;

import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.transaction.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionResponseDTO(
        UUID id,
        String description,
        BigDecimal amount,
        LocalDate date,
        TransactionType type,
        TransactionStatus status,
        String installmentLabel,
        String categoryName,
        UUID categoryId,
        boolean categoryArchived,
        String accountName,
        UUID accountId,
        UUID transferId
) {
    public static TransactionResponseDTO fromEntity(Transaction t) {
        String installLabel = null;
        if (t.getTotalInstallments() != null && t.getTotalInstallments() > 1) {
            installLabel = t.getInstallmentNumber() + "/" + t.getTotalInstallments();
        }
        return new TransactionResponseDTO(
                t.getId(),
                t.getDescription(),
                t.getAmount(),
                t.getDate(),
                t.getType(),
                t.getStatus(),
                installLabel,
                t.getCategory() != null ? t.getCategory().getName() : null,
                t.getCategory() != null ? t.getCategory().getId() : null,
                t.getCategory() != null && t.getCategory().getDeletedAt() != null,
                t.getAccount() != null ? t.getAccount().getName() : null,
                t.getAccount() != null ? t.getAccount().getId() : null,
                t.getTransferId()
        );
    }
}
