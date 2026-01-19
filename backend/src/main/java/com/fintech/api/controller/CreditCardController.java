package com.fintech.api.controller;

import com.fintech.api.domain.creditcard.CreditCard;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.CreateCreditCardDTO;
import com.fintech.api.dto.CreditCardResponseDTO;
import com.fintech.api.dto.UpdateCreditCardDTO; // Importe o novo DTO
import com.fintech.api.repository.CreditCardRepository;
import com.fintech.api.repository.UserRepository;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/credit-cards")
public class CreditCardController {

    @Autowired
    private CreditCardRepository repository;

    @Autowired
    private UserRepository userRepository;

    // --- CREATE (POST) ---
    @PostMapping
    @Transactional
    public ResponseEntity<CreditCardResponseDTO> create(
            @RequestBody @Valid CreateCreditCardDTO data,
            @AuthenticationPrincipal UserDetails userDetails,
            UriComponentsBuilder uriBuilder) {
        User user = getUser(userDetails);

        var card = new CreditCard();
        card.setName(data.name());
        card.setBrand(data.brand());
        card.setColor(data.color());
        card.setLastFourDigits(data.lastFourDigits());
        card.setLimitAmount(data.limitAmount());
        card.setClosingDay(data.closingDay());
        card.setDueDay(data.dueDay());
        card.setTenant(user.getTenant());
        card.setUser(user);

        repository.save(card);

        var uri = uriBuilder.path("/credit-cards/{id}").buildAndExpand(card.getId()).toUri();
        return ResponseEntity.created(uri).body(new CreditCardResponseDTO(card));
    }

    // --- READ (GET LIST) ---
    @GetMapping
    public ResponseEntity<List<CreditCardResponseDTO>> list(@AuthenticationPrincipal UserDetails userDetails) {
        User user = getUser(userDetails);
        var cards = repository.findByTenantId(user.getTenant().getId());
        var response = cards.stream().map(CreditCardResponseDTO::new).toList();
        return ResponseEntity.ok(response);
    }

    // --- READ ONE (GET BY ID) ---
    @GetMapping("/{id}")
    public ResponseEntity<CreditCardResponseDTO> getById(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails) {
        User user = getUser(userDetails); // Reusa nosso método auxiliar

        var card = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Cartão não encontrado"));

        // SEGURANÇA: Impede que eu acesse o cartão de outro Tenant sabendo o ID
        validateTenantAccess(card, user);

        return ResponseEntity.ok(new CreditCardResponseDTO(card));
    }

    // --- UPDATE (PUT) ---
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<CreditCardResponseDTO> update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateCreditCardDTO data,
            @AuthenticationPrincipal UserDetails userDetails) {
        User user = getUser(userDetails);

        // 1. Busca o cartão
        var card = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Cartão não encontrado"));

        // 2. SEGURANÇA: Verifica se o cartão pertence ao mesmo Tenant do usuário
        validateTenantAccess(card, user);

        // 3. Atualiza apenas os campos que foram enviados (diferentes de null)
        if (data.name() != null)
            card.setName(data.name());
        if (data.color() != null)
            card.setColor(data.color());
        if (data.lastFourDigits() != null)
            card.setLastFourDigits(data.lastFourDigits());
        if (data.limitAmount() != null)
            card.setLimitAmount(data.limitAmount());
        if (data.closingDay() != null)
            card.setClosingDay(data.closingDay());
        if (data.dueDay() != null)
            card.setDueDay(data.dueDay());

        // O @Transactional salva automaticamente, mas o save garante o retorno
        // atualizado
        repository.save(card);

        return ResponseEntity.ok(new CreditCardResponseDTO(card));
    }

    // --- DELETE (DELETE) ---
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails) {
        User user = getUser(userDetails);

        var card = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Cartão não encontrado"));

        // SEGURANÇA: Verifica se o cartão é do usuário/tenant correto
        validateTenantAccess(card, user);

        repository.delete(card);

        return ResponseEntity.noContent().build(); // Retorna 204 No Content
    }

    // --- Métodos Auxiliares para evitar repetição de código ---

    private User getUser(UserDetails userDetails) {
        return userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));
    }

    private void validateTenantAccess(CreditCard card, User user) {
        if (!card.getTenant().getId().equals(user.getTenant().getId())) {
            // Retornamos 404 para não dar pistas de que o ID existe mas é de outro usuário
            throw new RuntimeException("Cartão não encontrado ou acesso negado");
        }
    }
}