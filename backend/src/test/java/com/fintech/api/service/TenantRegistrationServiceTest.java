package com.fintech.api.service;

import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.dto.TenantRegistrationDTO;
import com.fintech.api.repository.TenantRepository;
import com.fintech.api.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantRegistrationServiceTest {

    @Mock TenantRepository tenantRepository;
    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock DefaultCategorySeeder categorySeeder;
    @InjectMocks TenantRegistrationService service;

    private TenantRegistrationDTO dto() {
        return new TenantRegistrationDTO("Família Teste", "Carlos Teste", "carlos@teste.com", "senha123");
    }

    @Test
    @DisplayName("register chama seedForTenant após criar o admin user")
    void register_callsSeedForTenant() {
        Tenant savedTenant = new Tenant();
        savedTenant.setId(UUID.randomUUID());
        savedTenant.setName("Família Teste");

        when(userRepository.existsByEmail("carlos@teste.com")).thenReturn(false);
        when(tenantRepository.save(any())).thenReturn(savedTenant);
        when(passwordEncoder.encode("senha123")).thenReturn("hashed");

        service.register(dto());

        verify(categorySeeder, times(1)).seedForTenant(savedTenant);
    }

    @Test
    @DisplayName("register propaga exceção do seeder (garante rollback)")
    void register_propagatesSeederException() {
        Tenant savedTenant = new Tenant();
        savedTenant.setId(UUID.randomUUID());

        when(userRepository.existsByEmail("carlos@teste.com")).thenReturn(false);
        when(tenantRepository.save(any())).thenReturn(savedTenant);
        when(passwordEncoder.encode("senha123")).thenReturn("hashed");
        doThrow(new RuntimeException("falha no seed")).when(categorySeeder).seedForTenant(any());

        assertThatThrownBy(() -> service.register(dto()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("falha no seed");
    }
}
