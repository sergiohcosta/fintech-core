package com.fintech.api.controller;

import com.fintech.api.domain.creditcard.CreditCard;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.CreateCreditCardDTO;
import com.fintech.api.dto.CreditCardResponseDTO;
import com.fintech.api.dto.UpdateCreditCardDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.openapi.CreditCardsApi;
import com.fintech.api.repository.CreditCardRepository;
import com.fintech.api.repository.UserRepository;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/credit-cards")
public class CreditCardController implements CreditCardsApi {

    @Autowired
    private CreditCardRepository repository;

    @Autowired
    private UserRepository userRepository;

    // --- CREATE (POST) ---
    @Override
    @PostMapping
    @Transactional
    public ResponseEntity<CreditCardResponseDTO> createCreditCard(
            @RequestBody @Valid CreateCreditCardDTO createCreditCardDTO) {
        User user = getAuthenticatedUser();

        var card = new CreditCard();
        card.setName(createCreditCardDTO.name());
        card.setBrand(createCreditCardDTO.brand());
        card.setColor(createCreditCardDTO.color());
        card.setLastFourDigits(createCreditCardDTO.lastFourDigits());
        card.setLimitAmount(createCreditCardDTO.limitAmount());
        card.setClosingDay(createCreditCardDTO.closingDay());
        card.setDueDay(createCreditCardDTO.dueDay());
        card.setTenant(user.getTenant());
        card.setUser(user);

        repository.save(card);

        return ResponseEntity.status(201).body(new CreditCardResponseDTO(card));
    }

    // --- READ (GET LIST) ---
    @Override
    @GetMapping
    public ResponseEntity<List<CreditCardResponseDTO>> listCreditCards() {
        User user = getAuthenticatedUser();
        var cards = repository.findByTenantId(user.getTenant().getId());
        var response = cards.stream().map(CreditCardResponseDTO::new).toList();
        return ResponseEntity.ok(response);
    }

    // --- READ ONE (GET BY ID) ---
    @Override
    @GetMapping("/{id}")
    public ResponseEntity<CreditCardResponseDTO> getCreditCard(@PathVariable UUID id) {
        User user = getAuthenticatedUser();

        var card = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Cartão não encontrado"));

        // SEGURANÇA: Impede que eu acesse o cartão de outro Tenant sabendo o ID
        validateTenantAccess(card, user);

        return ResponseEntity.ok(new CreditCardResponseDTO(card));
    }

    // --- UPDATE (PUT) ---
    @Override
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<CreditCardResponseDTO> updateCreditCard(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateCreditCardDTO updateCreditCardDTO) {
        User user = getAuthenticatedUser();

        // 1. Busca o cartão
        var card = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Cartão não encontrado"));

        // 2. SEGURANÇA: Verifica se o cartão pertence ao mesmo Tenant do usuário
        validateTenantAccess(card, user);

        // 3. Atualiza apenas os campos que foram enviados (diferentes de null)
        if (updateCreditCardDTO.name() != null)
            card.setName(updateCreditCardDTO.name());
        if (updateCreditCardDTO.color() != null)
            card.setColor(updateCreditCardDTO.color());
        if (updateCreditCardDTO.lastFourDigits() != null)
            card.setLastFourDigits(updateCreditCardDTO.lastFourDigits());
        if (updateCreditCardDTO.limitAmount() != null)
            card.setLimitAmount(updateCreditCardDTO.limitAmount());
        if (updateCreditCardDTO.closingDay() != null)
            card.setClosingDay(updateCreditCardDTO.closingDay());
        if (updateCreditCardDTO.dueDay() != null)
            card.setDueDay(updateCreditCardDTO.dueDay());

        // O @Transactional salva automaticamente, mas o save garante o retorno atualizado
        repository.save(card);

        return ResponseEntity.ok(new CreditCardResponseDTO(card));
    }

    // --- DELETE (DELETE) ---
    @Override
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> deleteCreditCard(@PathVariable UUID id) {
        User user = getAuthenticatedUser();

        var card = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Cartão não encontrado"));

        // SEGURANÇA: Verifica se o cartão é do usuário/tenant correto
        validateTenantAccess(card, user);

        repository.delete(card);

        return ResponseEntity.noContent().build(); // Retorna 204 No Content
    }

    // Obtém o usuário autenticado via SecurityContextHolder em vez de @AuthenticationPrincipal,
    // pois a interface OpenAPI não inclui esse parâmetro extra nas assinaturas dos métodos.
    private User getAuthenticatedUser() {
        UserDetails userDetails = (UserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new EntityNotFoundException("Usuário não encontrado"));
    }

    private void validateTenantAccess(CreditCard card, User user) {
        if (!card.getTenant().getId().equals(user.getTenant().getId())) {
            // Retornamos 404 para não dar pistas de que o ID existe mas é de outro usuário
            throw new EntityNotFoundException("Cartão não encontrado ou acesso negado");
        }
    }
}
