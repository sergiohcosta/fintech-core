package com.fintech.api.controller;

import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.invoice.Invoice;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.invoice.InvoiceResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.openapi.InvoicesApi;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.TransactionRepository;
import com.fintech.api.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class InvoiceController implements InvoicesApi {

    private final InvoiceService invoiceService;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    @GetMapping
    public ResponseEntity<List<InvoiceResponseDTO>> listInvoices(@RequestParam UUID accountId) {
        User user = getAuthenticatedUser();
        var account = accountRepository.findByIdAndTenant(accountId, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Conta não encontrada."));
        List<InvoiceResponseDTO> result = invoiceService.findByAccount(account).stream()
                .map(this::toDTO)
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<InvoiceResponseDTO> getInvoice(@PathVariable UUID id) {
        User user = getAuthenticatedUser();
        Invoice invoice = invoiceService.findByIdAndTenant(id, user.getTenant());
        return ResponseEntity.ok(toDTO(invoice));
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<InvoiceResponseDTO> closeInvoice(@PathVariable UUID id) {
        User user = getAuthenticatedUser();
        Invoice invoice = invoiceService.close(id, user.getTenant());
        return ResponseEntity.ok(toDTO(invoice));
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<InvoiceResponseDTO> payInvoice(@PathVariable UUID id) {
        User user = getAuthenticatedUser();
        Invoice invoice = invoiceService.pay(id, user.getTenant());
        return ResponseEntity.ok(toDTO(invoice));
    }

    private InvoiceResponseDTO toDTO(Invoice invoice) {
        BigDecimal total = transactionRepository.sumAmountByInvoice(invoice, TransactionStatus.CANCELLED);
        long count = transactionRepository.countByInvoice(invoice);
        return InvoiceResponseDTO.fromEntity(invoice, total, count);
    }

    private User getAuthenticatedUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
