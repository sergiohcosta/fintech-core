package com.fintech.api.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

import com.fintech.api.domain.enums.CardBrand;

public record CreateCreditCardDTO(

        @NotBlank(message = "O nome do cartão é obrigatório") String name,

        @NotNull(message = "A bandeira é obrigatória") CardBrand brand, // Ex: "MASTERCARD", "VISA"

        @Pattern(regexp = "^#([A-Fa-f0-9]{6})$", message = "Cor inválida") String color, // Ex: "#000000"

        @Size(min = 4, max = 4, message = "Informe apenas os últimos 4 dígitos") String lastFourDigits,

        @NotNull @Positive(message = "O limite deve ser positivo") BigDecimal limitAmount,

        @NotNull @Min(1) @Max(31) Integer closingDay,

        @NotNull @Min(1) @Max(31) Integer dueDay) {
}