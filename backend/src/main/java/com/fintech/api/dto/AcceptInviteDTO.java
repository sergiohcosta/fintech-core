package com.fintech.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AcceptInviteDTO(
    @NotBlank(message = "Token é obrigatório")   String token,
    @NotBlank(message = "Nome é obrigatório")    String name,
    @NotBlank(message = "Senha é obrigatória")
    @Size(max = 72, message = "Senha deve ter no máximo 72 caracteres")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$",
        message = "Senha deve ter no mínimo 8 caracteres, incluindo letra maiúscula, minúscula e número"
    )
    String password
) {}
