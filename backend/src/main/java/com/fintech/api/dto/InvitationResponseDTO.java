package com.fintech.api.dto;

import java.time.LocalDateTime;

public record InvitationResponseDTO(
    String token,
    String link,
    String email,
    LocalDateTime expiresAt
) {}
