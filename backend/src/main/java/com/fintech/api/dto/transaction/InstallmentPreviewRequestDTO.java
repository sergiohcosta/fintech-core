package com.fintech.api.dto.transaction;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Pedido de preview de parcelamento (D5): mesmo shape do create para os campos que
 * determinam o roteamento das parcelas (valor, data, nº de parcelas, conta), sem
 * descrição/tipo/status — o preview é read-only e não grava nada.
 */
public record InstallmentPreviewRequestDTO(
        @NotNull(message = "O valor é obrigatório") @DecimalMin(value = "0.01") BigDecimal amount,
        @NotNull(message = "A data é obrigatória") LocalDate date,
        // Integer nulável: @Min/@Max valem quando presente (null = 1 parcela, tratado no service).
        @Min(value = 1, message = "O número de parcelas deve ser entre 1 e 120")
        @Max(value = 120, message = "O número de parcelas deve ser entre 1 e 120")
        Integer totalInstallments,
        @NotNull(message = "A conta é obrigatória") UUID accountId
) {}
