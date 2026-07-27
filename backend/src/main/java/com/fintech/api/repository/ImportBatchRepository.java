package com.fintech.api.repository;

import com.fintech.api.domain.imports.ImportBatch;
import com.fintech.api.domain.tenant.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {

    // Escopo de tenant explícito: buscar por id cru vazaria batch de outra família.
    Optional<ImportBatch> findByIdAndTenant(UUID id, Tenant tenant);
}
