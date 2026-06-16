package com.fintech.api.config;

import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityFilterTest {

    @Mock TokenService tokenService;
    @Mock UserRepository userRepository;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock FilterChain filterChain;

    @InjectMocks
    SecurityFilter securityFilter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private User buildUser(boolean active) {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("teste@exemplo.com");
        user.setRole(UserRole.USER);
        user.setTenant(tenant);
        user.setActive(active);
        return user;
    }

    @Test
    @DisplayName("autentica normalmente quando usuário está ativo")
    void doFilterInternal_activeUser_setsAuthentication() throws Exception {
        User user = buildUser(true);
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        when(tokenService.validateToken("valid-token")).thenReturn(user.getEmail());
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        securityFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("não autentica quando usuário está inativo, mesmo com token válido")
    void doFilterInternal_inactiveUser_doesNotSetAuthentication() throws Exception {
        User user = buildUser(false);
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        when(tokenService.validateToken("valid-token")).thenReturn(user.getEmail());
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        securityFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }
}
