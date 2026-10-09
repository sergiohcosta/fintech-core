package com.fintech.api.dto.transaction;

import com.fintech.api.domain.enums.InvoiceStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Linha do preview de parcelamento (D5): o que o create faria com esta parcela.
 * {@code willCreate=false} significa que a parcela seria descartada porque a fatura
 * de destino existe com status fechado/pago — o frontend usa isso para pedir
 * confirmação antes de gravar.
 */
public record InstallmentPreviewDTO(
        int installmentNumber,
        int totalInstallments,
        BigDecimal amount,
        int referenceYear,
        int referenceMonth,
        LocalDate closingDate,
        LocalDate dueDate,
        UUID invoiceId,
        InvoiceStatus invoiceStatus,
        boolean willCreate
) {}
