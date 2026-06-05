package com.fintech.api.controller;

import com.fintech.api.config.SecurityConfigurations;
import com.fintech.api.config.SecurityFilter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.MemberDTO;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.MembersService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import({ SecurityConfigurations.class, SecurityFilter.class })
class MembersControllerTest {

    private MockMvc mockMvc;

    @Autowired WebApplicationContext context;
    @MockitoBean MembersService membersService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean TokenService tokenService;

    private User authenticatedUser;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());

        authenticatedUser = new User();
        authenticatedUser.setEmail("admin@test.com");
        authenticatedUser.setRole(UserRole.ADMIN);
        authenticatedUser.setTenant(tenant);

        when(tokenService.validateToken(anyString())).thenReturn(authenticatedUser.getEmail());
        when(userRepository.findByEmail(authenticatedUser.getEmail()))
                .thenReturn(Optional.of(authenticatedUser));
    }

    @Test
    @DisplayName("GET /api/members retorna 200 com lista de membros")
    void listMembers_returnsOk() throws Exception {
        MemberDTO member = new MemberDTO(UUID.randomUUID(), "João", "joao@test.com", UserRole.USER);
        when(membersService.list(any())).thenReturn(List.of(member));

        mockMvc.perform(get("/api/members")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("João"))
                .andExpect(jsonPath("$[0].email").value("joao@test.com"))
                .andExpect(jsonPath("$[0].role").value("USER"));
    }

    @Test
    @DisplayName("GET /api/members retorna 403 sem autenticação")
    void listMembers_withoutAuth_returns403() throws Exception {
        mockMvc.perform(get("/api/members"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/members retorna 403 para role USER")
    void listMembers_withUserRole_returns403() throws Exception {
        authenticatedUser.setRole(UserRole.USER);

        mockMvc.perform(get("/api/members")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden());
    }
}
