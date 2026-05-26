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

        // Novos Campos
        TransactionStatus status,
        String installmentLabel, // Retornaremos formatado: "1/12"

        String categoryName,
        String creditCardName) {
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
                t.getCreditCard() != null ? t.getCreditCard().getName() : null);
    }
}