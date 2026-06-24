package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.RecurringBudgetItemRequest;
import com.fintech.api.dto.budget.RecurringBudgetItemResponseDTO;
import com.fintech.api.service.RecurringBudgetItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import com.fintech.api.config.SecurityUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/recurring-budget-items")
@RequiredArgsConstructor
public class RecurringBudgetItemController {

    private final RecurringBudgetItemService service;

    @GetMapping
    public ResponseEntity<List<RecurringBudgetItemResponseDTO>> list(
            @RequestParam(required = false) Boolean active) {
        User user = getUser();
        return ResponseEntity.ok(service.listByTenant(user.getTenant(), active).stream()
            .map(RecurringBudgetItemResponseDTO::fromEntity).toList());
    }

    @PostMapping
    public ResponseEntity<RecurringBudgetItemResponseDTO> create(
            @Valid @RequestBody RecurringBudgetItemRequest req) {
        User user = getUser();
        return ResponseEntity.status(201)
            .body(RecurringBudgetItemResponseDTO.fromEntity(
                service.create(req, user.getTenant(), user)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RecurringBudgetItemResponseDTO> update(
            @PathVariable UUID id,
            @Valid @RequestBody RecurringBudgetItemRequest req) {
        User user = getUser();
        return ResponseEntity.ok(RecurringBudgetItemResponseDTO.fromEntity(
            service.update(id, req, user.getTenant())));
    }

    @PatchMapping("/{id}/reactivate")
    public ResponseEntity<RecurringBudgetItemResponseDTO> reactivate(@PathVariable UUID id) {
        User user = getUser();
        return ResponseEntity.ok(RecurringBudgetItemResponseDTO.fromEntity(
            service.reactivate(id, user.getTenant())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        User user = getUser();
        service.deactivate(id, user.getTenant());
        return ResponseEntity.noContent().build();
    }

    private User getUser() {
        return SecurityUtils.currentUser();
    }
}
