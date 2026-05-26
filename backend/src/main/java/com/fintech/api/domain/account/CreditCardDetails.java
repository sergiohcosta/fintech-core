package com.fintech.api.domain.account;

import com.fintech.api.domain.enums.CardBrand;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "credit_card_details")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CreditCardDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false, unique = true)
    @ToString.Exclude
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CardBrand brand;

    @Column(length = 4)
    private String lastFourDigits;

    @Column(precision = 19, scale = 2)
    private BigDecimal limitAmount;

    private Integer closingDay;
    private Integer dueDay;
}
