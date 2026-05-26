package com.fintech.api.repository;

import com.fintech.api.domain.creditcard.CreditCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreditCardRepository extends JpaRepository<CreditCard, UUID> {

    List<CreditCard> findByTenantId(UUID tenantId);

    Optional<CreditCard> findByIdAndTenantId(UUID id, UUID tenantId);
}