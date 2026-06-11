package com.fintech.api.repository;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.tenant.Tenant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface BudgetCycleRepository extends JpaRepository<BudgetCycle, UUID> {

    Optional<BudgetCycle> findByTenantAndStatus(Tenant tenant, BudgetCycleStatus status);

    Page<BudgetCycle> findAllByTenantOrderByStartDateDesc(Tenant tenant, Pageable pageable);

    @Query("""
        SELECT COUNT(c) > 0
        FROM BudgetCycle c
        WHERE c.tenant = :tenant
          AND c.startDate <= :endDate
          AND c.endDate >= :startDate
    """)
    boolean existsOverlap(
        @Param("tenant") Tenant tenant,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );
}
