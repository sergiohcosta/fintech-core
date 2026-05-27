package com.fintech.api.repository;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.account.CreditCardDetails;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CreditCardDetailsRepository extends JpaRepository<CreditCardDetails, UUID> {

    Optional<CreditCardDetails> findByAccount(Account account);
}
