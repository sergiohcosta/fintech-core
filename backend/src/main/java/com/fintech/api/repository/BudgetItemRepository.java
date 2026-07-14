package com.fintech.api.repository;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetItemSource;
import com.fintech.api.domain.recurrence.RecurrenceRule;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetItemRepository extends JpaRepository<BudgetItem, UUID> {

    @Query("""
        SELECT i FROM BudgetItem i
        LEFT JOIN FETCH i.category
        LEFT JOIN FETCH i.account
        LEFT JOIN FETCH i.transaction
        WHERE i.cycle = :cycle
        ORDER BY i.expectedDate ASC, i.type ASC
    """)
    List<BudgetItem> findAllByCycleWithDetails(@Param("cycle") BudgetCycle cycle);

    Optional<BudgetItem> findByTransaction(Transaction transaction);

    // #140: o item RECURRING PENDENTE do ciclo ABERTO que corresponde a uma ocorrência da regra.
    // Usado para vincular a transação materializada ao confirmar (evita contá-la como avulsa).
    @Query("""
        SELECT bi FROM BudgetItem bi
        WHERE bi.tenant = :tenant
          AND bi.source = com.fintech.api.domain.enums.BudgetItemSource.RECURRING
          AND bi.status = com.fintech.api.domain.enums.BudgetItemStatus.PENDING
          AND bi.recurrenceRule = :rule
          AND bi.recurrenceOccurrenceDate = :occurrence
          AND bi.cycle.status = com.fintech.api.domain.enums.BudgetCycleStatus.OPEN
    """)
    Optional<BudgetItem> findRecurringOccurrenceInOpenCycle(
        @Param("tenant") Tenant tenant,
        @Param("rule") RecurrenceRule rule,
        @Param("occurrence") LocalDate occurrence);

    boolean existsByCycleAndSource(BudgetCycle cycle, BudgetItemSource source);

    void deleteAllByCycle(BudgetCycle cycle);
}
