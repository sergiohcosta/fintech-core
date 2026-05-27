package com.fintech.api.dto.account;

import com.fintech.api.domain.enums.CardBrand;
import java.math.BigDecimal;

public record CreditCardDetailsResponseDTO(
        CardBrand brand,
        String lastFourDigits,
        BigDecimal limitAmount,
        Integer closingDay,
        Integer dueDay
) {}
