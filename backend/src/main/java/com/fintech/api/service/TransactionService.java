package com.fintech.api.service;

import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.creditcard.CreditCard;
import com.fintech.api.domain.enums.TransactionStatus;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
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
    public List<TransactionResponseDTO> create(TransactionRequestDTO dto, User user) {
        UUID tenantId = user.getTenant().getId();
        
        // 1. Validações Prévias (Category e Card) - Igual ao anterior
        Category category = null;
        if (dto.categoryId() != null) {
            category = categoryRepository.findByIdAndTenantId(dto.categoryId(), tenantId)
                    .orElseThrow(() -> new EntityNotFoundException("Categoria não encontrada."));
        }

        CreditCard card = null;
        if (dto.creditCardId() != null) {
            card = creditCardRepository.findByIdAndTenantId(dto.creditCardId(), tenantId)
                    .orElseThrow(() -> new EntityNotFoundException("Cartão não encontrado."));
        }

        // 2. Definição do Parcelamento
        int installments = (dto.totalInstallments() != null && dto.totalInstallments() > 0) 
                           ? dto.totalInstallments() 
                           : 1;

        // Se for parcelado, dividimos o valor. Cuidado com dízimas! (R$ 100 / 3)
        // Por simplicidade neste MVP, vamos dividir direto. 
        // Em V2, tratamos a diferença de centavos na última parcela.
        BigDecimal installmentAmount = dto.amount().divide(BigDecimal.valueOf(installments), 2, RoundingMode.HALF_EVEN);
        
        List<Transaction> createdTransactions = new ArrayList<>();

        // 3. Loop de Criação
        for (int i = 0; i < installments; i++) {
            LocalDate transactionDate = dto.date().plusMonths(i); // Incrementa 1 mês a cada loop
            
            Transaction t = Transaction.builder()
                .description(dto.description()) // A descrição é a mesma para todas
                .amount(installmentAmount)      // Valor da parcela
                .date(transactionDate)
                .type(dto.type())
                .status(dto.status() != null ? dto.status() : TransactionStatus.PENDING)
                .installmentNumber(i + 1)       // 1, 2, 3...
                .totalInstallments(installments)
                .tenant(user.getTenant())
                .user(user)
                .category(category)
                .creditCard(card)
                .build();

            createdTransactions.add(repository.save(t));
        }

        // Retorna a lista completa criada (ou apenas a primeira, dependendo da UX. Vamos retornar todas)
        return createdTransactions.stream()
                .map(TransactionResponseDTO::fromEntity)
                .toList();
    }
}