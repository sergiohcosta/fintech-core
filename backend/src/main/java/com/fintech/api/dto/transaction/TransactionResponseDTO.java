package com.fintech.api.dto.transaction;

import com.fintech.api.domain.enums.InvoiceStatus;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.transaction.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionResponseDTO(
        UUID id,
        String description,
        BigDecimal amount,
        LocalDate date,
        TransactionType type,
        TransactionStatus status,
        String installmentLabel,
        Integer installmentNumber,
        Integer totalInstallments,
        String categoryName,
        UUID categoryId,
        boolean categoryArchived,
        String accountName,
        UUID accountId,
        UUID transferId,
        UUID installmentGroupId,
        String installmentGroupDescription,
        UUID invoiceId,
        LocalDate invoiceDueDate,
        InvoiceStatus invoiceStatus
) {
    public static TransactionResponseDTO fromEntity(Transaction t) {
        String installLabel = null;
        if (t.getTotalInstallments() != null && t.getTotalInstallments() > 1) {
            installLabel = t.getInstallmentNumber() + "/" + t.getTotalInstallments();
        }
        var group = t.getInstallmentGroup();
        var invoice = t.getInvoice();
        return new TransactionResponseDTO(
                t.getId(),
                t.getDescription(),
                t.getAmount(),
                t.getDate(),
                t.getType(),
                t.getStatus(),
                installLabel,
                t.getInstallmentNumber(),
                t.getTotalInstallments(),
                t.getCategory() != null ? t.getCategory().getName() : null,
                t.getCategory() != null ? t.getCategory().getId() : null,
                t.getCategory() != null && t.getCategory().getDeletedAt() != null,
                t.getAccount() != null ? t.getAccount().getName() : null,
                t.getAccount() != null ? t.getAccount().getId() : null,
                t.getTransferId(),
                group != null ? group.getId() : null,
                group != null ? group.getDescription() : null,
                invoice != null ? invoice.getId() : null,
                invoice != null ? invoice.getDueDate() : null,
                invoice != null ? invoice.getStatus() : null
        );
    }
}
