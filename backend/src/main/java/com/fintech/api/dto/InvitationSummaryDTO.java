package com.fintech.api.dto;

import com.fintech.api.domain.enums.InvitationStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record InvitationSummaryDTO(
    UUID id,
    String email,
    InvitationStatus status,
    LocalDateTime createdAt,
    LocalDateTime expiresAt,
    String link
) {}
