package com.fintech.api.dto.installment;

import com.fintech.api.dto.transaction.TransactionResponseDTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InstallmentGroupResponseDTO(
        UUID id,
        String description,
        BigDecimal totalAmount,
        BigDecimal installmentAmount,
        int totalInstallments,
        long paidInstallments,
        long pendingInstallments,
        LocalDate nextDueDate,
        String categoryName,
        UUID categoryId,
        String accountName,
        UUID accountId,
        List<TransactionResponseDTO> transactions
) {}
