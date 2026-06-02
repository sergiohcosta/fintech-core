package com.fintech.api.dto.installment;

import com.fintech.api.domain.enums.TransactionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record InstallmentGroupPatchDTO(
        String description,
        UUID categoryId,
        UUID accountId,
        BigDecimal installmentAmount,
        TransactionStatus status,
        List<String> fields
) {}
