package com.fintech.api.domain.transaction;

import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.creditcard.CreditCard;
import com.fintech.api.domain.user.User;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String description; // "Almoço no Shopping"

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private LocalDate date; // Data da compra

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    // --- RELACIONAMENTOS ---

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user; // Dono da transação

    @ManyToOne
    @JoinColumn(name = "category_id")
    private Category category;

    // --- ORIGENS (Nullable) ---
    // Aqui implementamos a estratégia de Tabela Única.
    // Se for compra no crédito, preenche este campo.
    // Se for Pix (futuro), esse campo fica NULL.

    @ManyToOne
    @JoinColumn(name = "credit_card_id", nullable = true)
    private CreditCard creditCard;

    // Futuro:
    // private BankAccount bankAccount;
}