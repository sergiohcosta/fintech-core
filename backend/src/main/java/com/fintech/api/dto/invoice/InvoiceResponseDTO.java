package com.fintech.api.dto.invoice;

import com.fintech.api.domain.enums.InvoiceStatus;
import com.fintech.api.domain.invoice.Invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record InvoiceResponseDTO(
        UUID id,
        UUID accountId,
        String accountName,
        int referenceMonth,
        int referenceYear,
        String label,
        LocalDate closingDate,
        LocalDate dueDate,
        InvoiceStatus status,
        BigDecimal totalAmount,
        long transactionCount
) {
    private static final String[] MONTH_NAMES = {
        "", "Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho",
        "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro"
    };

    public static InvoiceResponseDTO fromEntity(Invoice invoice, BigDecimal totalAmount, long transactionCount) {
        return new InvoiceResponseDTO(
                invoice.getId(),
                invoice.getAccount().getId(),
                invoice.getAccount().getName(),
                invoice.getReferenceMonth(),
                invoice.getReferenceYear(),
                MONTH_NAMES[invoice.getReferenceMonth()] + "/" + invoice.getReferenceYear(),
                invoice.getClosingDate(),
                invoice.getDueDate(),
                invoice.getStatus(),
                totalAmount,
                transactionCount
        );
    }
}
