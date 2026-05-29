package com.fintech.api.dto.transfer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TransferResponseDTO(
        UUID transferId,
        UUID fromLegId,
        UUID toLegId,
        BigDecimal amount,
        LocalDate date,
        String description,
        String fromAccount,
        String toAccount
) {}
