package com.fintech.api.dto.transaction;

import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionRequestDTO(

        @NotBlank(message = "A descrição é obrigatória") String description,

        @NotNull(message = "O valor é obrigatório") @DecimalMin(value = "0.01", message = "O valor deve ser positivo") BigDecimal amount,

        @NotNull(message = "A data é obrigatória") LocalDate date,

        @NotNull(message = "O tipo é obrigatório") TransactionType type,

        TransactionStatus status, // Pode ser nulo

        Integer totalInstallments, // Pode ser nulo

        UUID categoryId,
        UUID creditCardId) {
}