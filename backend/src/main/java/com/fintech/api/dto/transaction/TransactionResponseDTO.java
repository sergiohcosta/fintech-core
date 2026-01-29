package com.fintech.api.dto.transaction;

import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionResponseDTO(
    UUID id,
    String description,
    BigDecimal amount,
    LocalDate date,
    TransactionType type,
    String categoryName,
    String creditCardName
) {
    public static TransactionResponseDTO fromEntity(Transaction t) {
        return new TransactionResponseDTO(
            t.getId(),
            t.getDescription(),
            t.getAmount(),
            t.getDate(),
            t.getType(),
            t.getCategory() != null ? t.getCategory().getName() : null,
            t.getCreditCard() != null ? t.getCreditCard().getName() : null
        );
    }
}