package com.fintech.api.dto.invoice;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

public record InvoicePayDTO(
        @NotNull(message = "A conta de origem é obrigatória") UUID sourceAccountId,
        // Opcional (#199): ausência vira LocalDate.now() no service. Validação de data futura
        // também fica no service, junto das demais regras de negócio de pay().
        LocalDate paymentDate
) {}
