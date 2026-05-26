package com.fintech.api.service;

import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.TenantRegistrationDTO;
import com.fintech.api.repository.TenantRepository;
import com.fintech.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor // Cria um construtor com os campos 'final' (Injeção de Dependência limpa)
public class TenantRegistrationService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional // O pulo do gato: Atomicidade (Tudo ou Nada)
    public Tenant register(TenantRegistrationDTO dto) {
        // 1. Validação de Regra de Negócio
        if (userRepository.existsByEmail(dto.adminEmail())) {
            throw new IllegalArgumentException("Este email já está cadastrado.");
        }

        // 2. Criar e Salvar o Tenant (A "Empresa/Família")
        Tenant tenant = new Tenant();
        tenant.setName(dto.name());
        tenant.setDocument(dto.document());

        // O método save atualiza o objeto 'tenant' com o ID gerado pelo banco
        tenant = tenantRepository.save(tenant);

        // 3. Criar e Salvar o Usuário Admin
        User adminUser = new User();
        adminUser.setName(dto.adminName());
        adminUser.setEmail(dto.adminEmail());
        adminUser.setRole(UserRole.ADMIN);
        adminUser.setTenant(tenant); // Vincula o usuário ao tenant criado acima

        // Criptografar a senha antes de salvar
        String encodedPassword = passwordEncoder.encode(dto.password());
        adminUser.setPasswordHash(encodedPassword);

        userRepository.save(adminUser);

        // 4. Retorno
        return tenant;
    }
}