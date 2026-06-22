package com.fintech.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.api.config.SecurityConfigurations;
import com.fintech.api.config.SecurityFilter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.TenantSettingsPatchRequest;
import com.fintech.api.repository.TenantRepository;
import com.fintech.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import({ SecurityConfigurations.class, SecurityFilter.class })
class TenantControllerTest {

    private MockMvc mockMvc;

    @Autowired WebApplicationContext context;
    @MockitoBean TenantRepository tenantRepository;
    @MockitoBean UserRepository userRepository;
    @MockitoBean TokenService tokenService;

    private final ObjectMapper mapper = new ObjectMapper();
    private User authUser;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());

        authUser = new User();
        authUser.setEmail("admin@test.com");
        authUser.setRole(UserRole.ADMIN);
        authUser.setTenant(tenant);

        when(tokenService.validateToken(anyString())).thenReturn(authUser.getEmail());
        when(userRepository.findByEmail(authUser.getEmail())).thenReturn(Optional.of(authUser));
        when(tenantRepository.findById(any())).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("PATCH /api/tenant/settings retorna 204 para ADMIN")
    void patchSettings_withAdminRole_returnsNoContent() throws Exception {
        TenantSettingsPatchRequest req = new TenantSettingsPatchRequest(15);

        mockMvc.perform(patch("/api/tenant/settings")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /api/tenant/settings retorna 403 para role USER")
    void patchSettings_withUserRole_returns403() throws Exception {
        authUser.setRole(UserRole.USER);
        TenantSettingsPatchRequest req = new TenantSettingsPatchRequest(15);

        mockMvc.perform(patch("/api/tenant/settings")
                        .header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /api/tenant/settings retorna 403 sem autenticação")
    void patchSettings_withoutAuth_returns403() throws Exception {
        TenantSettingsPatchRequest req = new TenantSettingsPatchRequest(15);

        mockMvc.perform(patch("/api/tenant/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }
}
