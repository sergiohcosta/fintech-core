package com.fintech.api.repository;

import com.fintech.api.domain.imports.StagedTransaction;
import com.fintech.api.domain.tenant.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StagedTransactionRepository extends JpaRepository<StagedTransaction, UUID> {

    // Filtra pelo tenant DENORMALIZADO na própria linha (defesa nº1 contra vazamento — spec §3),
    // sem depender de JOIN em import_batches. Ordem estável por created_at para a UI.
    List<StagedTransaction> findAllByBatch_IdAndTenantOrderByCreatedAt(UUID batchId, Tenant tenant);

    Optional<StagedTransaction> findByIdAndTenant(UUID id, Tenant tenant);
}
