package com.fintech.api.config;

import com.fintech.api.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityUtilsTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void currentUser_retornaPrincipal_quandoAutenticado() {
        User user = new User();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, List.of()));

        assertThat(SecurityUtils.currentUser()).isSameAs(user);
    }

    @Test
    void currentUser_lancaAccessDenied_quandoContextoVazio() {
        assertThatThrownBy(SecurityUtils::currentUser)
            .isInstanceOf(AccessDeniedException.class);
    }
}
