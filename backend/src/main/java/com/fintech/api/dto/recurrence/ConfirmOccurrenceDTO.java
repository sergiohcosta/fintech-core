package com.fintech.api.dto.recurrence;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.time.LocalDate;

// Ambos opcionais: confirmar sem corpo usa base_amount da regra e a própria data da ocorrência.
public record ConfirmOccurrenceDTO(
        @DecimalMin("0.01") BigDecimal amount,
        LocalDate date) {
}
