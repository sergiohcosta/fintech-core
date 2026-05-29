package com.fintech.api.repository;

import com.fintech.api.domain.category.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    // Busca raízes ativas apenas do TENANT logado
    List<Category> findAllByTenantIdAndParentIsNullAndDeletedAtIsNull(UUID tenantId);

    // Busca todas as raízes (ativas + arquivadas) — usado quando includeArchived=true
    List<Category> findAllByTenantIdAndParentIsNull(UUID tenantId);

    // Segurança: só retorna categorias ativas do Tenant
    Optional<Category> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    // Validação de duplicidade no mesmo nível — ignora categorias arquivadas
    boolean existsByNameAndTenantIdAndParentIdAndDeletedAtIsNull(String name, UUID tenantId, UUID parentId);
}
