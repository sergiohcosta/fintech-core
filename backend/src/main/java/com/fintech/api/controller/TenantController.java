package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.TenantSettingsPatchRequest;
import com.fintech.api.repository.TenantRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tenant")
@RequiredArgsConstructor
public class TenantController {

    private final TenantRepository tenantRepository;

    @GetMapping("/settings")
    public ResponseEntity<TenantSettingsPatchRequest> getSettings() {
        return ResponseEntity.ok(new TenantSettingsPatchRequest(getUser().getTenant().getBudgetCycleStartDay()));
    }

    @PatchMapping("/settings")
    public ResponseEntity<Void> patchSettings(@Valid @RequestBody TenantSettingsPatchRequest req) {
        var tenant = getUser().getTenant();
        tenant.setBudgetCycleStartDay(req.budgetCycleStartDay());
        tenantRepository.save(tenant);
        return ResponseEntity.noContent().build();
    }

    private User getUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
