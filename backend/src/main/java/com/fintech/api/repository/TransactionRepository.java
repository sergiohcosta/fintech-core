package com.fintech.api.repository;

import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    List<Transaction> findAllByTenantOrderByDateDesc(Tenant tenant);

    Optional<Transaction> findByIdAndTenant(UUID id, Tenant tenant);

    List<Transaction> findByTransferIdAndTenant(UUID transferId, Tenant tenant);

    // COALESCE garante 0 quando não há transações no período (SUM de conjunto vazio = null no SQL)
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM Transaction t
            WHERE t.tenant = :tenant
              AND t.type = :type
              AND t.status <> :excluded
              AND t.date BETWEEN :start AND :end
            """)
    BigDecimal sumByTenantAndTypeAndPeriod(
            @Param("tenant") Tenant tenant,
            @Param("type") TransactionType type,
            @Param("excluded") TransactionStatus excluded,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end
    );
}
