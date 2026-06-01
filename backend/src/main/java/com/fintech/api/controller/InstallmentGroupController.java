package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.installment.DeleteInstallmentResultDTO;
import com.fintech.api.dto.installment.InstallmentGroupPatchDTO;
import com.fintech.api.dto.installment.InstallmentGroupResponseDTO;
import com.fintech.api.service.InstallmentGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/installment-groups")
@RequiredArgsConstructor
public class InstallmentGroupController {

    private final InstallmentGroupService service;

    @GetMapping
    public ResponseEntity<List<InstallmentGroupResponseDTO>> listInstallmentGroups() {
        return ResponseEntity.ok(service.findAll(getAuthenticatedUser()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InstallmentGroupResponseDTO> getInstallmentGroup(@PathVariable UUID id) {
        return ResponseEntity.ok(service.findById(id, getAuthenticatedUser()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<DeleteInstallmentResultDTO> deleteInstallmentGroup(@PathVariable UUID id) {
        return ResponseEntity.ok(service.deleteGroup(id, getAuthenticatedUser()));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<InstallmentGroupResponseDTO> patchInstallmentGroup(
            @PathVariable UUID id,
            @RequestBody InstallmentGroupPatchDTO dto) {
        return ResponseEntity.ok(service.patch(id, dto, getAuthenticatedUser()));
    }

    private User getAuthenticatedUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
