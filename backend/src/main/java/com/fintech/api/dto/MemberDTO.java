package com.fintech.api.dto;

import com.fintech.api.domain.enums.UserRole;
import java.util.UUID;

public record MemberDTO(
    UUID id,
    String name,
    String email,
    UserRole role
) {}
