package com.fintech.api.dto;

import com.fintech.api.domain.creditcard.CreditCard;
import com.fintech.api.domain.enums.CardBrand;

import java.math.BigDecimal;
import java.util.UUID;

public record CreditCardResponseDTO(
        UUID id,
        String name,
        CardBrand brand,
        String color,
        String lastFourDigits,
        BigDecimal limitAmount,
        Integer closingDay,
        Integer dueDay) {
    // Construtor auxiliar para converter Entidade -> DTO
    public CreditCardResponseDTO(CreditCard card) {
        this(
                card.getId(),
                card.getName(),
                card.getBrand(),
                card.getColor(),
                card.getLastFourDigits(),
                card.getLimitAmount(),
                card.getClosingDay(),
                card.getDueDay());
    }
}