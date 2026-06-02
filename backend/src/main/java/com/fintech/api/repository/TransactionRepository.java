package com.fintech.api.repository;

import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.installment.InstallmentGroup;
import com.fintech.api.domain.invoice.Invoice;
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

    List<Transaction> findByTransferIdAndTenant(UUID transferId, Tenant tenant);

    // Usado na listagem — evita N+1 para category, account, installmentGroup e invoice
    @Query("""
            SELECT t FROM Transaction t
            LEFT JOIN FETCH t.installmentGroup
            LEFT JOIN FETCH t.category
            LEFT JOIN FETCH t.account
            LEFT JOIN FETCH t.invoice
            WHERE t.tenant = :tenant
            ORDER BY t.date DESC
            """)
    List<Transaction> findAllByTenantWithDetails(@Param("tenant") Tenant tenant);

    @Query("""
            SELECT t FROM Transaction t
            LEFT JOIN FETCH t.installmentGroup
            LEFT JOIN FETCH t.category
            LEFT JOIN FETCH t.account
            WHERE t.tenant = :tenant AND t.invoice = :invoice
            ORDER BY t.date DESC
            """)
    List<Transaction> findAllByTenantAndInvoiceWithDetails(
            @Param("tenant") Tenant tenant,
            @Param("invoice") Invoice invoice);

    // Todas as parcelas do grupo, ordenadas por número da parcela
    List<Transaction> findByInstallmentGroupOrderByInstallmentNumberAsc(InstallmentGroup group);

    // Parcelas a partir de um número (inclusive), para escopo THIS_AND_NEXT no delete
    List<Transaction> findByInstallmentGroupAndInstallmentNumberGreaterThanEqualOrderByInstallmentNumberAsc(
            InstallmentGroup group, int installmentNumber);

    // Parcelas futuras com status PENDING — usadas na propagação do update
    @Query("""
            SELECT t FROM Transaction t
            WHERE t.installmentGroup = :group
              AND t.installmentNumber > :number
              AND t.status = :status
            ORDER BY t.installmentNumber ASC
            """)
    List<Transaction> findFuturePendingInGroup(
            @Param("group") InstallmentGroup group,
            @Param("number") int installmentNumber,
            @Param("status") TransactionStatus status);

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

    // Conta transações não canceladas no período (independente de tipo).
    // Retorna 0 quando o período não tem movimentação — usado para detectar empty state.
    @Query("""
            SELECT COUNT(t)
            FROM Transaction t
            WHERE t.tenant = :tenant
              AND t.status <> :excluded
              AND t.date BETWEEN :start AND :end
            """)
    long countByTenantAndPeriod(
            @Param("tenant") Tenant tenant,
            @Param("excluded") TransactionStatus excluded,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end
    );

    // Saldo líquido acumulado: soma income e subtrai expense de TODAS as transações
    // não canceladas, filtradas pelas contas marcadas como countInLiquidBalance.
    // Sem filtro de período — representa a posição financeira atual.
    @Query("""
            SELECT COALESCE(SUM(
                CASE WHEN t.type = :incomeType THEN t.amount ELSE -t.amount END
            ), 0)
            FROM Transaction t
            WHERE t.tenant = :tenant
              AND t.status <> :excluded
              AND t.account.countInLiquidBalance = true
            """)
    BigDecimal sumNetLiquidBalanceByTenant(
            @Param("tenant") Tenant tenant,
            @Param("incomeType") TransactionType incomeType,
            @Param("excluded") TransactionStatus excluded
    );
}
