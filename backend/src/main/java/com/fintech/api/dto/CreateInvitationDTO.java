package com.fintech.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateInvitationDTO(
    @NotBlank(message = "Email é obrigatório")
    @Email(message = "Email inválido")
    String email
) {}
