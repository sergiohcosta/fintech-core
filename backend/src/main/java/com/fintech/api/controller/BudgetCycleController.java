package com.fintech.api.controller;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.*;
import com.fintech.api.service.BudgetCycleService;
import com.fintech.api.service.BudgetItemService;
import com.fintech.api.service.BudgetSummaryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/budget-cycles")
@RequiredArgsConstructor
public class BudgetCycleController {

    private final BudgetCycleService cycleService;
    private final BudgetItemService itemService;
    private final BudgetSummaryService summaryService;

    @GetMapping
    public ResponseEntity<Page<BudgetCycleResponseDTO>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        User user = getUser();
        Page<BudgetCycleResponseDTO> result = cycleService
            .listByTenant(user.getTenant(), PageRequest.of(page, size))
            .map(this::buildResponse);
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<BudgetCycleResponseDTO> open(@Valid @RequestBody BudgetCycleOpenRequest req) {
        User user = getUser();
        var cycle = cycleService.open(user.getTenant(), user, req);
        return ResponseEntity.status(201)
            .body(buildResponse(cycle));
    }

    @GetMapping("/current")
    public ResponseEntity<BudgetCycleResponseDTO> current() {
        User user = getUser();
        return cycleService.findOpenByTenant(user.getTenant())
            .map(c -> ResponseEntity.ok(buildResponse(c)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<BudgetCycleResponseDTO> get(@PathVariable UUID id) {
        User user = getUser();
        var cycle = cycleService.findByIdAndTenant(id, user.getTenant());
        return ResponseEntity.ok(buildResponse(cycle));
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<BudgetCycleResponseDTO> close(@PathVariable UUID id) {
        User user = getUser();
        var cycle = cycleService.close(id, user.getTenant());
        return ResponseEntity.ok(buildResponse(cycle));
    }

    @PostMapping("/{id}/sync-installments")
    public ResponseEntity<BudgetCycleResponseDTO> syncInstallments(@PathVariable UUID id) {
        User user = getUser();
        var cycle = cycleService.syncInstallments(id, user.getTenant(), user);
        return ResponseEntity.ok(buildResponse(cycle));
    }

    @GetMapping("/{cycleId}/items")
    public ResponseEntity<List<BudgetItemResponseDTO>> listItems(@PathVariable UUID cycleId) {
        User user = getUser();
        var cycle = cycleService.findByIdAndTenant(cycleId, user.getTenant());
        return ResponseEntity.ok(cycleService.listItems(cycle).stream()
            .map(BudgetItemResponseDTO::fromEntity).toList());
    }

    @PostMapping("/{cycleId}/items")
    public ResponseEntity<BudgetItemResponseDTO> createItem(
            @PathVariable UUID cycleId,
            @Valid @RequestBody BudgetItemCreateRequest req) {
        User user = getUser();
        var cycle = cycleService.findByIdAndTenant(cycleId, user.getTenant());
        return ResponseEntity.status(201)
            .body(BudgetItemResponseDTO.fromEntity(
                itemService.create(cycle, req, user.getTenant(), user)));
    }

    private BudgetCycleResponseDTO buildResponse(BudgetCycle cycle) {
        var items = cycleService.listItems(cycle);
        var summary = summaryService.calculateSummary(cycle, items, LocalDate.now());
        return BudgetCycleResponseDTO.fromEntity(cycle, items, summary);
    }

    private User getUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
