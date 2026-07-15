package com.fintech.api.controller;

import com.fintech.api.config.SecurityUtils;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.recurrence.ConfirmOccurrenceDTO;
import com.fintech.api.dto.recurrence.RecurrenceRuleCreateDTO;
import com.fintech.api.dto.recurrence.RecurrenceRulePatchDTO;
import com.fintech.api.dto.recurrence.RecurrenceRuleResponseDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.openapi.RecurrenceRulesApi;
import com.fintech.api.service.RecurrenceRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/recurrence-rules")
@RequiredArgsConstructor
public class RecurrenceRulesController implements RecurrenceRulesApi {

    private final RecurrenceRuleService service;

    @Override
    @GetMapping
    public ResponseEntity<List<RecurrenceRuleResponseDTO>> listRecurrenceRules() {
        return ResponseEntity.ok(service.findAll(getAuthenticatedUser()));
    }

    @Override
    @PostMapping
    public ResponseEntity<RecurrenceRuleResponseDTO> createRecurrenceRule(
            @RequestBody @Valid RecurrenceRuleCreateDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(dto, getAuthenticatedUser()));
    }

    @Override
    @GetMapping("/{id}")
    public ResponseEntity<RecurrenceRuleResponseDTO> getRecurrenceRule(@PathVariable UUID id) {
        return ResponseEntity.ok(service.findById(id, getAuthenticatedUser()));
    }

    @Override
    @PatchMapping("/{id}")
    public ResponseEntity<RecurrenceRuleResponseDTO> patchRecurrenceRule(
            @PathVariable UUID id, @RequestBody @Valid RecurrenceRulePatchDTO dto) {
        return ResponseEntity.ok(service.patch(id, dto, getAuthenticatedUser()));
    }

    @Override
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelRecurrenceRule(@PathVariable UUID id) {
        service.cancel(id, getAuthenticatedUser());
        return ResponseEntity.noContent().build();
    }

    @Override
    @PatchMapping("/{id}/reactivate")
    public ResponseEntity<RecurrenceRuleResponseDTO> reactivateRecurrenceRule(@PathVariable UUID id) {
        return ResponseEntity.ok(service.reactivate(id, getAuthenticatedUser()));
    }

    @Override
    @PostMapping("/{id}/occurrences/{date}/confirm")
    public ResponseEntity<TransactionResponseDTO> confirmOccurrence(
            @PathVariable UUID id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody(required = false) @Valid ConfirmOccurrenceDTO body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.confirmOccurrence(id, date, body, getAuthenticatedUser()));
    }

    @Override
    @PostMapping("/{id}/occurrences/{date}/skip")
    public ResponseEntity<Void> skipOccurrence(
            @PathVariable UUID id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        service.skipOccurrence(id, date, getAuthenticatedUser());
        return ResponseEntity.noContent().build();
    }

    private User getAuthenticatedUser() {
        return SecurityUtils.currentUser();
    }
}
