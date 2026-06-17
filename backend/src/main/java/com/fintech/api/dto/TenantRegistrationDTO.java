package com.fintech.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Olha como é limpo. Não precisa de 'class', nem getters/setters.
// Já colocamos validações aqui (Bean Validation)
public record TenantRegistrationDTO(
                @NotBlank(message = "Nome é obrigatório") String name,

                @NotBlank(message = "Nome do administrador é obrigatório") String adminName,

                @NotBlank(message = "Email é obrigatório") @Email(message = "Email inválido") String adminEmail,

                @NotBlank(message = "Senha é obrigatória")
                @Size(max = 72, message = "Senha deve ter no máximo 72 caracteres")
                @Pattern(
                    regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$",
                    message = "Senha deve ter no mínimo 8 caracteres, incluindo letra maiúscula, minúscula e número"
                )
                String password) {
}
