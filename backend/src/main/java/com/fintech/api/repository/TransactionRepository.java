package com.fintech.api.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    List<Transaction> findAllByTenantOrderByDateDesc(Tenant tenant);

}
