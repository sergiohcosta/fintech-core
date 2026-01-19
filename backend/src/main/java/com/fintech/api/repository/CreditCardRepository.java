package com.fintech.api.repository;

import com.fintech.api.domain.creditcard.CreditCard;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface CreditCardRepository extends JpaRepository<CreditCard, UUID> {
}