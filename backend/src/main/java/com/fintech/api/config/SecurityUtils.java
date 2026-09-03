package com.fintech.api.config;

import com.fintech.api.domain.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Acesso centralizado ao usuário autenticado.
 *
 * <p>Lê o principal do {@link SecurityContextHolder} (e não via {@code @AuthenticationPrincipal})
 * porque as interfaces geradas pelo OpenAPI não comportam o parâmetro extra nas assinaturas dos
 * métodos dos controllers.
 *
 * <p>O {@code SecurityFilter} já garante autenticação nos endpoints protegidos; a checagem de
 * nulidade aqui é a rede de segurança que faltava — concentra num único ponto o que antes era a
 * cadeia {@code getAuthentication().getPrincipal()} repetida (e potencialmente nula, SonarQube
 * S2259) em cada controller. Em vez de propagar o risco de NPE, falha explicitamente com 403.
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /** Retorna o usuário autenticado; lança {@link AccessDeniedException} se o contexto estiver vazio. */
    public static User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            throw new AccessDeniedException("Nenhum usuário autenticado no contexto de segurança.");
        }
        return user;
    }

    /**
     * Variante tolerante de {@link #currentUser()} — {@code null} em vez de lançar quando não
     * há usuário autenticado. Uso restrito a código de infraestrutura (ex.:
     * {@code TenantRlsAspect}) para o qual "não autenticado" é um caso normal (endpoint
     * público), não um erro de acesso.
     */
    public static User currentUserOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof User user ? user : null;
    }
}
