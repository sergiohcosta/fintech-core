package com.fintech.api.repository;

import com.fintech.api.domain.budget.RecurringBudgetItem;
import com.fintech.api.domain.tenant.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecurringBudgetItemRepository extends JpaRepository<RecurringBudgetItem, UUID> {

    List<RecurringBudgetItem> findAllByTenantAndActiveTrueOrderByDayOfMonthAscDescriptionAsc(Tenant tenant);

    Optional<RecurringBudgetItem> findByIdAndTenant(UUID id, Tenant tenant);
}
