package com.fintech.api.domain.transaction;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.installment.InstallmentGroup;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @NotBlank(message = "A descrição é obrigatória")
    @Column(nullable = false)
    private String description;

    @NotNull(message = "O valor é obrigatório")
    @DecimalMin(value = "0.01", message = "O valor deve ser positivo")
    @Column(nullable = false)
    private BigDecimal amount;

    @NotNull(message = "A data é obrigatória")
    @Column(nullable = false)
    private LocalDate date;

    @NotNull(message = "O tipo da transação é obrigatório")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type; // INCOME, EXPENSE

    // --- SEGURANÇA & MULTITENANCY ---

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    @ToString.Exclude // <--- EVITA LOOP INFINITO E ERRO DE MEMÓRIA
    private Tenant tenant;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude // <--- EVITA LOOP INFINITO
    private User user;

    // --- RELACIONAMENTOS OPCIONAIS ---

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    @ToString.Exclude
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    @ToString.Exclude
    private Account account;

    // Liga as duas pernas de uma transferência (nullable para transações comuns)
    private UUID transferId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "installment_group_id")
    @ToString.Exclude
    private InstallmentGroup installmentGroup;

    @NotNull(message = "O status é obrigatório")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default // Garante que se não informado, nasce como PENDING
    private TransactionStatus status = TransactionStatus.PENDING;

    @NotNull(message = "O número de parcelas é obrigatório")
    @Column(nullable = false)
    @Builder.Default // Garante que se não informado, nasce como 1
    private Integer installmentNumber = 1;

    @NotNull(message = "O número total de parcelas é obrigatório")
    @Column(nullable = false)
    @Builder.Default // Garante que se não informado, nasce como 1
    private Integer totalInstallments = 1;

    // --- AUDITORIA ---
    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}