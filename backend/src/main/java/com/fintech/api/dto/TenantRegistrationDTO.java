package com.fintech.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// Olha como é limpo. Não precisa de 'class', nem getters/setters.
// Já colocamos validações aqui (Bean Validation)
public record TenantRegistrationDTO(
                @NotBlank(message = "Nome é obrigatório") String name,

                @NotBlank(message = "Nome do administrador é obrigatório") String adminName,

                @NotBlank(message = "Email é obrigatório") @Email(message = "Email inválido") String adminEmail,

                @NotBlank(message = "Senha é obrigatória") String password) {
}