package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.account.AccountCreateDTO;
import com.fintech.api.dto.account.AccountResponseDTO;
import com.fintech.api.dto.account.AccountUpdateDTO;
import com.fintech.api.openapi.AccountsApi;
import com.fintech.api.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController implements AccountsApi {

    private final AccountService accountService;

    @Override
    @GetMapping
    public ResponseEntity<List<AccountResponseDTO>> listAccounts() {
        return ResponseEntity.ok(accountService.findAll(getAuthenticatedUser()));
    }

    @Override
    @PostMapping
    public ResponseEntity<AccountResponseDTO> createAccount(@Valid @RequestBody AccountCreateDTO dto) {
        AccountResponseDTO created = accountService.create(dto, getAuthenticatedUser());
        URI uri = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(uri).body(created);
    }

    @Override
    @GetMapping("/{id}")
    public ResponseEntity<AccountResponseDTO> getAccount(@PathVariable UUID id) {
        return ResponseEntity.ok(accountService.findById(id, getAuthenticatedUser()));
    }

    @Override
    @PutMapping("/{id}")
    public ResponseEntity<AccountResponseDTO> updateAccount(
            @PathVariable UUID id, @Valid @RequestBody AccountUpdateDTO dto) {
        return ResponseEntity.ok(accountService.update(id, dto, getAuthenticatedUser()));
    }

    @Override
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAccount(@PathVariable UUID id) {
        accountService.archive(id, getAuthenticatedUser());
        return ResponseEntity.noContent().build();
    }

    private User getAuthenticatedUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
