package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.invoice.InvoiceResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.openapi.InvoicesApi;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class InvoiceController implements InvoicesApi {

    private final InvoiceService invoiceService;
    private final AccountRepository accountRepository;

    @GetMapping
    public ResponseEntity<List<InvoiceResponseDTO>> listInvoices(@RequestParam UUID accountId) {
        User user = getAuthenticatedUser();
        var account = accountRepository.findByIdAndTenant(accountId, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Conta não encontrada."));
        return ResponseEntity.ok(invoiceService.listDTOs(account));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InvoiceResponseDTO> getInvoice(@PathVariable UUID id) {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(invoiceService.getDTO(id, user.getTenant()));
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<InvoiceResponseDTO> closeInvoice(@PathVariable UUID id) {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(invoiceService.close(id, user.getTenant()));
    }

    @Override
    public ResponseEntity<InvoiceResponseDTO> payInvoice(UUID id, UUID sourceAccountId) {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(invoiceService.pay(id, user.getTenant(), user, sourceAccountId));
    }

    private User getAuthenticatedUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
