package com.fintech.api.dto.budget;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RecurringItemPreviewDTO(
    String description,
    BigDecimal amount,
    String type,           // "INCOME" | "EXPENSE"
    LocalDate expectedDate
) {}
