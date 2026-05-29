package com.fintech.api.repository;

import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    List<Transaction> findAllByTenantOrderByDateDesc(Tenant tenant);

    Optional<Transaction> findByIdAndTenant(UUID id, Tenant tenant);

    // Contagem de transações vinculadas a qualquer uma das categorias informadas (subtree)
    long countByCategoryIdInAndTenantId(Collection<UUID> categoryIds, UUID tenantId);

    // Reassociação em massa via SQL nativo — JPQL não suporta SET de associação por ID
    @Modifying
    @Query(value = """
            UPDATE transactions
               SET category_id = :targetId
             WHERE category_id IN :sourceIds
               AND tenant_id   = :tenantId
            """, nativeQuery = true)
    void reassignCategories(
            @Param("sourceIds") Collection<UUID> sourceIds,
            @Param("targetId") UUID targetId,
            @Param("tenantId") UUID tenantId
    );

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
