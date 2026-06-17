package com.fintech.api.dto.budget;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InstallmentItemPreviewDTO(
    String description,
    BigDecimal amount,
    LocalDate expectedDate,
    String accountName
) {}
