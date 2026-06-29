package com.fintech.api.repository;

import com.fintech.api.domain.enums.RecurrenceStatus;
import com.fintech.api.domain.recurrence.RecurrenceRule;
import com.fintech.api.domain.tenant.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecurrenceRuleRepository extends JpaRepository<RecurrenceRule, UUID> {
    List<RecurrenceRule> findByTenantAndStatus(Tenant tenant, RecurrenceStatus status);
    Optional<RecurrenceRule> findByIdAndTenant(UUID id, Tenant tenant);
}
