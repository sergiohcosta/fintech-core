package com.fintech.api.domain.account;

import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType type;

    @Column(length = 7)
    private String color;

    @Column(length = 50)
    private String icon;

    // Defaults definidos explicitamente no AccountService por tipo; não usar @Builder.Default
    // para forçar o service a sempre setar esses valores conscientemente.
    @Column(nullable = false)
    private boolean countInLiquidBalance;

    @Column(nullable = false)
    private boolean countInNetWorth;

    // Campo nomeado 'active' (não 'isActive') para evitar o bug do Lombok:
    // boolean isX gera getter isIsX(). 'active' gera isActive() corretamente.
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    @ToString.Exclude
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    @ToString.Exclude
    private User createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
