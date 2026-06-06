package com.fintech.api.dto.invoice;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record InvoicePayDTO(
        @NotNull(message = "A conta de origem é obrigatória") UUID sourceAccountId
) {}
