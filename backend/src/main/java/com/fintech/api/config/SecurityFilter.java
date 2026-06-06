package com.fintech.api.config;

import com.fintech.api.domain.user.User;
import com.fintech.api.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
public class SecurityFilter extends OncePerRequestFilter {

    @Autowired
    TokenService tokenService;
    @Autowired
    UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var token = this.recoverToken(request);

        if (token != null) {
            var email = tokenService.validateToken(token);

            if (email == null || email.isBlank()) {
                log.warn("Token inválido ou expirado [path={} method={}]",
                        request.getRequestURI(), request.getMethod());
            } else {
                UserDetails userDetails = userRepository.findByEmail(email).orElse(null);
                if (userDetails == null) {
                    log.warn("Token válido mas usuário não encontrado [email={}]", email);
                } else {
                    var authentication = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    // Popula MDC com contexto do usuário autenticado para todos os logs subsequentes
                    User user = (User) userDetails;
                    if (user.getId() != null) MDC.put("userId", user.getId().toString());
                    if (user.getTenant() != null && user.getTenant().getId() != null)
                        MDC.put("tenantId", user.getTenant().getId().toString());
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private String recoverToken(HttpServletRequest request) {
        var authHeader = request.getHeader("Authorization");
        if (authHeader == null)
            return null;
        return authHeader.replace("Bearer ", "");
    }
}
