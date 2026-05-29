package com.fintech.api.dto.transfer;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TransferRequestDTO(
        @NotNull(message = "A conta de origem é obrigatória") UUID fromAccountId,
        @NotNull(message = "A conta de destino é obrigatória") UUID toAccountId,
        @NotNull(message = "O valor é obrigatório")
        @DecimalMin(value = "0.01", message = "O valor deve ser positivo") BigDecimal amount,
        @NotNull(message = "A data é obrigatória") LocalDate date,
        String description
) {}
