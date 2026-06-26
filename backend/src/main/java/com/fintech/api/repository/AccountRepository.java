package com.fintech.api.repository;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findAllByTenantAndActiveTrueOrderByName(Tenant tenant);

    Optional<Account> findByIdAndTenant(UUID id, Tenant tenant);

    @Query("""
        SELECT COALESCE(SUM(
            CASE WHEN t.type = :incomeType THEN t.amount ELSE -t.amount END
        ), 0)
        FROM Transaction t
        WHERE t.account = :account
          AND t.status = :paidStatus
    """)
    BigDecimal calculateBalance(
        @Param("account") Account account,
        @Param("incomeType") TransactionType incomeType,
        @Param("paidStatus") TransactionStatus paidStatus
    );

    @Query("""
        SELECT COALESCE(SUM(
            CASE WHEN t.type = :incomeType THEN t.amount ELSE -t.amount END
        ), 0)
        FROM Transaction t
        WHERE t.account.tenant.id = :tenantId
          AND t.account.countInLiquidBalance = true
          AND t.account.active = true
          AND t.status = :paidStatus
          AND t.date < :startDate
    """)
    // O corte `t.date < :startDate` é o que torna isto um saldo de ABERTURA:
    // soma apenas o que já era caixa ANTES do ciclo começar. Sem ele, transações
    // PAID dentro do período entrariam aqui E de novo em realized/unplanned → dupla contagem.
    // `<` (e não `<=`) porque o dia startDate pertence ao ciclo (intervalo [start, end] inclusivo).
    BigDecimal sumLiquidBalanceByTenant(
        @Param("tenantId") UUID tenantId,
        @Param("incomeType") TransactionType incomeType,
        @Param("paidStatus") TransactionStatus paidStatus,
        @Param("startDate") LocalDate startDate
    );
}
