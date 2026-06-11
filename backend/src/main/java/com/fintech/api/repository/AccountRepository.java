package com.fintech.api.repository;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
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
          AND t.status <> :cancelledStatus
    """)
    BigDecimal calculateBalance(
        @Param("account") Account account,
        @Param("incomeType") TransactionType incomeType,
        @Param("cancelledStatus") TransactionStatus cancelledStatus
    );

    @Query("""
        SELECT COALESCE(SUM(
            CASE WHEN t.type = :incomeType THEN t.amount ELSE -t.amount END
        ), 0)
        FROM Transaction t
        WHERE t.account.tenant.id = :tenantId
          AND t.account.countInLiquidBalance = true
          AND t.account.active = true
          AND t.status <> :cancelledStatus
    """)
    BigDecimal sumLiquidBalanceByTenant(
        @Param("tenantId") UUID tenantId,
        @Param("incomeType") TransactionType incomeType,
        @Param("cancelledStatus") TransactionStatus cancelledStatus
    );
}
