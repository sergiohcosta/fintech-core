package com.fintech.api.repository;

import com.fintech.api.domain.imports.ImportBatch;
import com.fintech.api.domain.tenant.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {

    // Escopo de tenant explícito: buscar por id cru vazaria batch de outra família.
    Optional<ImportBatch> findByIdAndTenant(UUID id, Tenant tenant);

    // Dedup por arquivo (Onda 4): o MESMO hash pode existir em dois tenants (duas famílias que
    // importam o mesmo extrato-modelo, por exemplo) — o filtro por tenant impede que isso vaze
    // como "já existe" entre famílias diferentes. O mais recente é o que importa pra mensagem 409.
    Optional<ImportBatch> findFirstByTenantAndSourceHashOrderByCreatedAtDesc(Tenant tenant, String sourceHash);
}
