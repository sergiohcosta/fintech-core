package com.fintech.api.repository;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetItemSource;
import com.fintech.api.domain.transaction.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetItemRepository extends JpaRepository<BudgetItem, UUID> {

    @Query("""
        SELECT i FROM BudgetItem i
        LEFT JOIN FETCH i.category
        LEFT JOIN FETCH i.account
        WHERE i.cycle = :cycle
        ORDER BY i.expectedDate ASC, i.type ASC
    """)
    List<BudgetItem> findAllByCycleWithDetails(@Param("cycle") BudgetCycle cycle);

    Optional<BudgetItem> findByTransactionAndCycleNot(Transaction transaction, BudgetCycle cycle);

    boolean existsByCycleAndSource(BudgetCycle cycle, BudgetItemSource source);
}
