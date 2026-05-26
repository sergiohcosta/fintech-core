package com.fintech.api.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record UpdateCreditCardDTO(
    @Size(min = 1, message = "O nome não pode ser vazio")
    String name,

    @Pattern(regexp = "^#([A-Fa-f0-9]{6})$", message = "Cor inválida")
    String color,

    @Size(min = 4, max = 4)
    String lastFourDigits,

    @Positive
    BigDecimal limitAmount,

    @Min(1) @Max(31)
    Integer closingDay,

    @Min(1) @Max(31)
    Integer dueDay
) {}