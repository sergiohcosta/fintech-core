package com.fintech.api.controller;

import com.fintech.api.domain.creditcard.CreditCard;
import com.fintech.api.domain.user.User; // Importa a SUA classe correta
import com.fintech.api.dto.CreateCreditCardDTO;
import com.fintech.api.dto.CreditCardResponseDTO;
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

@RestController
@RequestMapping("/credit-cards")
public class CreditCardController {

    @Autowired
    private CreditCardRepository repository;

    @Autowired
    private UserRepository userRepository;

    @PostMapping
    @Transactional // OBRIGATÓRIO: Mantém a sessão aberta para carregar o Tenant LAZY
    public ResponseEntity<CreditCardResponseDTO> create(
            @RequestBody @Valid CreateCreditCardDTO data,
            @AuthenticationPrincipal UserDetails userDetails, // O Spring injeta o usuário logado aqui
            UriComponentsBuilder uriBuilder) {
        // 1. Buscamos o User completo no banco para garantir que o Tenant venha junto
        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));

        var card = new CreditCard();
        card.setName(data.name());
        card.setBrand(data.brand());
        card.setColor(data.color());
        card.setLastFourDigits(data.lastFourDigits());
        card.setLimitAmount(data.limitAmount());
        card.setClosingDay(data.closingDay());
        card.setDueDay(data.dueDay());

        // 2. Vínculos (Isso funciona porque sua classe User tem o getTenant() via
        // @Data)
        card.setTenant(user.getTenant());
        card.setUser(user);

        repository.save(card);

        var uri = uriBuilder.path("/credit-cards/{id}").buildAndExpand(card.getId()).toUri();
        return ResponseEntity.created(uri).body(new CreditCardResponseDTO(card));
    }
}