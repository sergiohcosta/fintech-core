package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.BudgetItemLinkRequest;
import com.fintech.api.dto.budget.BudgetItemResponseDTO;
import com.fintech.api.dto.budget.BudgetItemUpdateRequest;
import com.fintech.api.service.BudgetItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/budget-items")
@RequiredArgsConstructor
public class BudgetItemController {

    private final BudgetItemService itemService;

    @PutMapping("/{id}")
    public ResponseEntity<BudgetItemResponseDTO> update(
            @PathVariable UUID id,
            @Valid @RequestBody BudgetItemUpdateRequest req) {
        User user = getUser();
        var item = itemService.findByIdAndTenant(id, user.getTenant());
        return ResponseEntity.ok(BudgetItemResponseDTO.fromEntity(itemService.update(item, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        User user = getUser();
        var item = itemService.findByIdAndTenant(id, user.getTenant());
        itemService.delete(item);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/link")
    public ResponseEntity<BudgetItemResponseDTO> link(
            @PathVariable UUID id,
            @Valid @RequestBody BudgetItemLinkRequest req) {
        User user = getUser();
        var item = itemService.findByIdAndTenant(id, user.getTenant());
        return ResponseEntity.ok(BudgetItemResponseDTO.fromEntity(
            itemService.link(item, req.transactionId())));
    }

    @DeleteMapping("/{id}/link")
    public ResponseEntity<BudgetItemResponseDTO> unlink(@PathVariable UUID id) {
        User user = getUser();
        var item = itemService.findByIdAndTenant(id, user.getTenant());
        return ResponseEntity.ok(BudgetItemResponseDTO.fromEntity(itemService.unlink(item)));
    }

    private User getUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
