package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.RecurringBudgetItemRequest;
import com.fintech.api.dto.budget.RecurringBudgetItemResponseDTO;
import com.fintech.api.service.RecurringBudgetItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/recurring-budget-items")
@RequiredArgsConstructor
public class RecurringBudgetItemController {

    private final RecurringBudgetItemService service;

    @GetMapping
    public ResponseEntity<List<RecurringBudgetItemResponseDTO>> list() {
        User user = getUser();
        return ResponseEntity.ok(service.listActive(user.getTenant()).stream()
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

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        User user = getUser();
        service.deactivate(id, user.getTenant());
        return ResponseEntity.noContent().build();
    }

    private User getUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
