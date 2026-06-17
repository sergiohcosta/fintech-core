package com.fintech.api.config;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService();
        ReflectionTestUtils.setField(tokenService, "secret", "test-secret-key-for-unit-tests-only");
    }

    private User buildUser(UserRole role) {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("Tenant Teste");

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setName("Usuário Teste");
        user.setEmail("teste@exemplo.com");
        user.setRole(role);
        user.setTenant(tenant);
        return user;
    }

    @Test
    @DisplayName("generateToken emite claim role = ADMIN para usuário ADMIN")
    void generateToken_emitsAdminRole() {
        User admin = buildUser(UserRole.ADMIN);
        String token = tokenService.generateToken(admin);
        DecodedJWT decoded = JWT.decode(token);
        assertThat(decoded.getClaim("role").asString()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("generateToken emite claim role = USER para usuário USER")
    void generateToken_emitsUserRole() {
        User user = buildUser(UserRole.USER);
        String token = tokenService.generateToken(user);
        DecodedJWT decoded = JWT.decode(token);
        assertThat(decoded.getClaim("role").asString()).isEqualTo("USER");
    }

    @Test
    @DisplayName("generateToken expira ~2h após emissão, independente do timezone default da JVM")
    void generateToken_expiresAfterTwoHours_independentOfJvmTimezone() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            Instant before = Instant.now();
            User user = buildUser(UserRole.USER);
            String token = tokenService.generateToken(user);
            DecodedJWT decoded = JWT.decode(token);
            Instant expiresAt = decoded.getExpiresAtAsInstant();

            Instant expected = before.plusSeconds(2 * 3600);
            long diffSeconds = Math.abs(expiresAt.getEpochSecond() - expected.getEpochSecond());
            assertThat(diffSeconds).isLessThan(5);
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
