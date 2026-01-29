package com.fintech.api.service;

import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.creditcard.CreditCard;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.transaction.TransactionRequestDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.CreditCardRepository;
import com.fintech.api.repository.TransactionRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository repository;
    private final CategoryRepository categoryRepository;
    private final CreditCardRepository creditCardRepository;

    @Transactional(readOnly = true)
    public List<TransactionResponseDTO> findAll(User user) {
        // CORREÇÃO: Passamos o objeto Tenant inteiro
        return repository.findAllByTenantOrderByDateDesc(user.getTenant())
                .stream()
                .map(TransactionResponseDTO::fromEntity)
                .toList();
    }

    @Transactional
    public TransactionResponseDTO create(TransactionRequestDTO dto, User user) {
        UUID tenantId = user.getTenant().getId();

        // Builder inicial
        var transactionBuilder = Transaction.builder()
                .description(dto.description())
                .amount(dto.amount())
                .date(dto.date())
                .type(dto.type())
                .tenant(user.getTenant()) // Vinculo de Segurança Obrigatório
                .user(user); // Auditoria

        // 1. Validação Segura de Categoria
        if (dto.categoryId() != null) {
            Category category = categoryRepository.findByIdAndTenantId(dto.categoryId(), tenantId)
                    .orElseThrow(() -> new EntityNotFoundException("Categoria não encontrada."));
            transactionBuilder.category(category);
        }

        // 2. Validação Segura de Cartão
        if (dto.creditCardId() != null) {
            CreditCard card = creditCardRepository.findByIdAndTenantId(dto.creditCardId(), tenantId)
                    .orElseThrow(() -> new EntityNotFoundException("Cartão não encontrado."));
            transactionBuilder.creditCard(card);
        }

        Transaction saved = repository.save(transactionBuilder.build());
        return TransactionResponseDTO.fromEntity(saved);
    }

    // TODO: Futuramente implementar delete e update com a mesma validação de
    // tenantId
}