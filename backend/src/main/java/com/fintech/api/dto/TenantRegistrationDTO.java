package com.fintech.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
// import org.hibernate.validator.constraints.br.CPF;

// Olha como é limpo. Não precisa de 'class', nem getters/setters.
// Já colocamos validações aqui (Bean Validation)
public record TenantRegistrationDTO(
        @NotBlank(message = "Nome é obrigatório") String name,

        @NotBlank(message = "Documento é obrigatório")
        // @CPF -> Futuramente podemos descomentar para validar CPF real
        String document,

        @NotBlank(message = "Nome do administrador é obrigatório") String adminName,

        @NotBlank(message = "Email é obrigatório") @Email(message = "Email inválido") String adminEmail,

        @NotBlank(message = "Senha é obrigatória") String password) {
}