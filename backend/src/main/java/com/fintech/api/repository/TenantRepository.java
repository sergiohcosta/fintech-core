package com.fintech.api.repository;

import com.fintech.api.domain.tenant.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    // Só de estender JpaRepository, você já ganhou: save, findAll, findById,
    // delete...
}