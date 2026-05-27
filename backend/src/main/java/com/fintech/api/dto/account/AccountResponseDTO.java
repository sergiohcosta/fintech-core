package com.fintech.api.dto.account;

import com.fintech.api.domain.enums.AccountType;
import java.math.BigDecimal;
import java.util.UUID;

public record AccountResponseDTO(
        UUID id,
        String name,
        AccountType type,
        String color,
        String icon,
        boolean countInLiquidBalance,
        boolean countInNetWorth,
        boolean active,
        BigDecimal balance,
        CreditCardDetailsResponseDTO creditCardDetails
) {}
