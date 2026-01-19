package com.fintech.api.domain.creditcard;

import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Table(name = "credit_cards")
@Entity(name = "credit_cards")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class CreditCard {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;
    private String brand;
    private String color;
    
    @Column(name = "last_four_digits")
    private String lastFourDigits;

    @Column(name = "limit_amount")
    private BigDecimal limitAmount;

    @Column(name = "closing_day")
    private Integer closingDay;

    @Column(name = "due_day")
    private Integer dueDay;

    @ManyToOne
    @JoinColumn(name = "tenant_id")
    private Tenant tenant;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    @CreationTimestamp // Preenche automaticamente na criação
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp // Atualiza automaticamente a cada save()
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}