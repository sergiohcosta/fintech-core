package com.fintech.api.dto.transaction;

import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TransactionUpdateDTO(
        @Size(min = 1, message = "A descrição não pode ser vazia")
        String description,

        @DecimalMin(value = "0.01", message = "O valor deve ser positivo")
        BigDecimal amount,

        LocalDate date,
        TransactionType type,
        TransactionStatus status,
        UUID categoryId,
        UUID accountId,
        List<String> propagate
) {}
