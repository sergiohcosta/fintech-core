package com.fintech.api.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    List<Transaction> findAllByTenantOrderByDateDesc(Tenant tenant);

    // Busca por id E tenant — garante que um tenant não acesse transação de outro
    Optional<Transaction> findByIdAndTenant(UUID id, Tenant tenant);

}
