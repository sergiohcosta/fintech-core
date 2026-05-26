package com.fintech.api.repository;

import com.fintech.api.domain.category.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    // Busca raízes apenas do TENANT logado
    List<Category> findAllByTenantIdAndParentIsNull(UUID tenantId);

    // Segurança: Garante que só retorna se pertencer ao Tenant
    Optional<Category> findByIdAndTenantId(UUID id, UUID tenantId);

    // Validação de Duplicidade no mesmo nível dentro do Tenant
    boolean existsByNameAndTenantIdAndParentId(String name, UUID tenantId, UUID parentId);
}