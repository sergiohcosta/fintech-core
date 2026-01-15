package com.fintech.api.controller;

import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.dto.TenantRegistrationDTO;
import com.fintech.api.service.TenantRegistrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final TenantRegistrationService registrationService;

    @PostMapping("/register")
    public ResponseEntity<Tenant> register(@RequestBody @Valid TenantRegistrationDTO dto) {
        Tenant newTenant = registrationService.register(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(newTenant);
    }
}