package com.fintech.api.repository;

import com.fintech.api.domain.installment.InstallmentGroup;
import com.fintech.api.domain.tenant.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InstallmentGroupRepository extends JpaRepository<InstallmentGroup, UUID> {
    Optional<InstallmentGroup> findByIdAndTenant(UUID id, Tenant tenant);
    List<InstallmentGroup> findByTenantOrderByCreatedAtDesc(Tenant tenant);
}
