package com.fintech.api.dto.transaction;

import com.fintech.api.domain.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionRequestDTO(
        String description,
        BigDecimal amount,
        LocalDate date,
        TransactionType type,
        UUID categoryId, // Opcional
        UUID creditCardId // Opcional
) {
}
