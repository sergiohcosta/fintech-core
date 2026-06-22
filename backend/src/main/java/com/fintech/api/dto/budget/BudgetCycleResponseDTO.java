package com.fintech.api.dto.budget;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record BudgetCycleResponseDTO(
    UUID id,
    LocalDate startDate,
    LocalDate endDate,
    BigDecimal openingBalance,
    BudgetCycleStatus status,
    BudgetCycleSummaryDTO summary,
    List<BudgetItemResponseDTO> items,
    List<TransactionResponseDTO> unplannedTransactions
) {
    public static BudgetCycleResponseDTO fromEntity(
            BudgetCycle cycle,
            List<BudgetItem> items,
            List<Transaction> unplanned,
            BudgetCycleSummaryDTO summary) {

        List<BudgetItemResponseDTO> itemDTOs = items.stream()
            .map(BudgetItemResponseDTO::fromEntity)
            .toList();
        List<TransactionResponseDTO> unplannedDTOs = unplanned.stream()
            .map(TransactionResponseDTO::fromEntity)
            .toList();

        return new BudgetCycleResponseDTO(
            cycle.getId(),
            cycle.getStartDate(),
            cycle.getEndDate(),
            cycle.getOpeningBalance(),
            cycle.getStatus(),
            summary,
            itemDTOs,
            unplannedDTOs
        );
    }
}
