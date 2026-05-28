package com.fintech.api.dto;

import jakarta.validation.constraints.NotBlank;

public record AcceptInviteDTO(
    @NotBlank(message = "Token é obrigatório")   String token,
    @NotBlank(message = "Nome é obrigatório")    String name,
    @NotBlank(message = "Senha é obrigatória")   String password
) {}
