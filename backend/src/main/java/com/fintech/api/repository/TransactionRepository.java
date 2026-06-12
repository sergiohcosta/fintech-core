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

    // Usado na listagem — evita N+1 para category, account, installmentGroup e invoice.
    // Ordenação final (por mês de referência vs. data da transação) é feita em memória
    // no TransactionService, pois JPQL não computa LocalDate.of(referenceYear, referenceMonth, 1).
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

    // startDate e endDate nunca são null aqui — o service substitui por sentinelas (1000-01-01 /
    // 9999-12-31) quando o usuário não filtra por período. Isso evita o padrão ":param IS NULL"
    // para LocalDate no JPQL, que faz o PostgreSQL emitir "could not determine data type of
    // parameter $N" (sem contexto de tipo em IS NULL). Com sentinelas, os parâmetros sempre
    // aparecem em comparações com colunas DATE, fornecendo o contexto necessário.
    // accountIdCount = 0 significa "sem filtro de conta"; accountIdCount > 0 ativa o IN.
    // Essa abordagem evita ":accountIds IS NULL" no JPQL, que não é suportado pelo Hibernate 6
    // para coleções — a inferência de tipo falha da mesma forma que LocalDate IS NULL.
    @Query("""
            SELECT t FROM Transaction t
            LEFT JOIN FETCH t.installmentGroup
            LEFT JOIN FETCH t.category
            LEFT JOIN FETCH t.account
            LEFT JOIN FETCH t.invoice inv
            WHERE t.tenant = :tenant
              AND (:accountIdCount = 0 OR t.account.id IN :accountIds)
              AND (:status    IS NULL OR t.status = :status)
              AND (:type      IS NULL OR t.type = :type)
              AND (
                (t.installmentGroup IS NOT NULL AND inv IS NOT NULL AND inv.dueDate >= :startDate AND inv.dueDate <= :endDate)
                OR ((t.installmentGroup IS NULL OR inv IS NULL) AND t.date >= :startDate AND t.date <= :endDate)
              )
            ORDER BY t.date DESC
            """)
    List<Transaction> findAllByTenantWithFilters(
            @Param("tenant")         Tenant tenant,
            @Param("accountIds")     List<UUID> accountIds,
            @Param("accountIdCount") int accountIdCount,
            @Param("status")         TransactionStatus status,
            @Param("type")           TransactionType type,
            @Param("startDate")      LocalDate startDate,
            @Param("endDate")        LocalDate endDate
    );

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

    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM Transaction t
            WHERE t.invoice = :invoice AND t.status <> :excluded
            """)
    BigDecimal sumAmountByInvoice(
            @Param("invoice") Invoice invoice,
            @Param("excluded") TransactionStatus excluded);

    long countByInvoice(Invoice invoice);

    // Atualiza em batch todas as transações de uma fatura com status específico.
    // @Modifying exige @Transactional no caller — evita loop N+1 de saves.
    @Modifying
    @Query("""
        UPDATE Transaction t
           SET t.status = :newStatus
         WHERE t.invoice = :invoice
           AND t.status = :currentStatus
        """)
    int updateStatusByInvoiceAndStatus(
        @Param("invoice") Invoice invoice,
        @Param("currentStatus") TransactionStatus currentStatus,
        @Param("newStatus") TransactionStatus newStatus
    );

    // Para transações de cartão, usa invoice.dueDate como referência de mês.
    // Para demais contas (sem fatura), usa transaction.date.
    // LEFT JOIN explícito é obrigatório: t.invoice.dueDate em WHERE gera INNER JOIN implícito
    // no Hibernate, excluindo transações sem fatura e quebrando o branch invoice IS NULL.
    @Query("""
        SELECT t FROM Transaction t
        LEFT JOIN FETCH t.installmentGroup ig
        LEFT JOIN FETCH t.invoice inv
        WHERE t.account.tenant.id = :tenantId
          AND t.installmentGroup IS NOT NULL
          AND inv IS NOT NULL
          AND inv.dueDate BETWEEN :startDate AND :endDate
          AND t.status <> :cancelledStatus
        ORDER BY ig.id, inv.dueDate
    """)
    List<Transaction> findInstallmentsInPeriodByTenant(
        @Param("tenantId") UUID tenantId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        @Param("cancelledStatus") TransactionStatus cancelledStatus
    );

    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM Transaction t
            LEFT JOIN t.invoice inv
            WHERE t.tenant = :tenant
              AND t.type = :type
              AND t.status <> :excluded
              AND (
                (inv IS NOT NULL AND inv.dueDate BETWEEN :start AND :end)
                OR
                (inv IS NULL AND t.date BETWEEN :start AND :end)
              )
            """)
    BigDecimal sumByTenantAndTypeAndPeriod(
            @Param("tenant") Tenant tenant,
            @Param("type") TransactionType type,
            @Param("excluded") TransactionStatus excluded,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end
    );

    @Query("""
            SELECT COUNT(t)
            FROM Transaction t
            LEFT JOIN t.invoice inv
            WHERE t.tenant = :tenant
              AND t.status <> :excluded
              AND (
                (inv IS NOT NULL AND inv.dueDate BETWEEN :start AND :end)
                OR
                (inv IS NULL AND t.date BETWEEN :start AND :end)
              )
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
