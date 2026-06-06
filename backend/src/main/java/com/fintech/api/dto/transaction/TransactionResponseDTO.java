package com.fintech.api.dto.transaction;

import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.InvoiceStatus;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.transaction.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
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
        String categoryPath,
        String categoryIcon,
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
        var cat = t.getCategory();
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
                cat != null ? cat.getName() : null,
                cat != null ? cat.getId() : null,
                cat != null && cat.getDeletedAt() != null,
                cat != null ? buildCategoryPath(cat) : null,
                cat != null ? cat.getIcon() : null,
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

    // Percorre a cadeia pai→filho e monta "Avô → Pai → Filho".
    // Funciona com lazy loading pois é chamado dentro de @Transactional.
    private static String buildCategoryPath(Category category) {
        var parts = new ArrayDeque<String>();
        var curr = category;
        while (curr != null) {
            parts.addFirst(curr.getName());
            curr = curr.getParent();
        }
        return String.join(" → ", parts);
    }
}
