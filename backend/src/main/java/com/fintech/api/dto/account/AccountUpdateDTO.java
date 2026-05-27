package com.fintech.api.dto.account;

public record AccountUpdateDTO(
        String name,
        String color,
        String icon,
        Boolean countInLiquidBalance,
        Boolean countInNetWorth
) {}
