package com.fintech.api.repository;

import com.fintech.api.domain.invitation.Invitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {
    Optional<Invitation> findByToken(String token);
    boolean existsByEmailAndTenantIdAndUsedFalseAndExpiresAtAfter(
            String email, UUID tenantId, LocalDateTime now);
}
