package com.fintech.api.dto.account;

import com.fintech.api.domain.enums.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AccountCreateDTO(
        @NotBlank(message = "O nome é obrigatório")
        @Size(max = 100)
        String name,

        @NotNull(message = "O tipo é obrigatório")
        AccountType type,

        String color,
        String icon,
        Boolean countInLiquidBalance,
        Boolean countInNetWorth,
        CreditCardDetailsDTO creditCardDetails
) {}
