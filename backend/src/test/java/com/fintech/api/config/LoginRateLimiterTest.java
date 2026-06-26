package com.fintech.api.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {

    private LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new LoginRateLimiter();
        ReflectionTestUtils.setField(limiter, "maxAttempts", 3);
        // @PostConstruct não é invocado fora de contexto Spring; inicializa window manualmente.
        ReflectionTestUtils.setField(limiter, "window", Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("isBlocked retorna false enquanto tentativas estão abaixo do limite")
    void isBlocked_belowThreshold_returnsFalse() {
        limiter.registerFailure("user@test.com");
        limiter.registerFailure("user@test.com");

        assertThat(limiter.isBlocked("user@test.com")).isFalse();
    }

    @Test
    @DisplayName("isBlocked retorna true após atingir o número máximo de falhas")
    void isBlocked_atThreshold_returnsTrue() {
        limiter.registerFailure("user@test.com");
        limiter.registerFailure("user@test.com");
        limiter.registerFailure("user@test.com");

        assertThat(limiter.isBlocked("user@test.com")).isTrue();
    }

    @Test
    @DisplayName("chave é normalizada por case (mesmo email em maiúsculas conta no mesmo bucket)")
    void isBlocked_isCaseInsensitive() {
        limiter.registerFailure("USER@test.com");
        limiter.registerFailure("user@test.com");
        limiter.registerFailure("User@Test.com");

        assertThat(limiter.isBlocked("user@TEST.com")).isTrue();
    }

    @Test
    @DisplayName("registerSuccess limpa o contador de falhas")
    void registerSuccess_clearsFailureCount() {
        limiter.registerFailure("user@test.com");
        limiter.registerFailure("user@test.com");
        limiter.registerFailure("user@test.com");
        assertThat(limiter.isBlocked("user@test.com")).isTrue();

        limiter.registerSuccess("user@test.com");

        assertThat(limiter.isBlocked("user@test.com")).isFalse();
    }

    @Test
    @DisplayName("bloqueio expira após a janela de tempo configurada")
    void isBlocked_expiresAfterWindow() throws InterruptedException {
        ReflectionTestUtils.setField(limiter, "window", Duration.ofMillis(50));
        limiter.registerFailure("user@test.com");
        limiter.registerFailure("user@test.com");
        limiter.registerFailure("user@test.com");
        assertThat(limiter.isBlocked("user@test.com")).isTrue();

        Thread.sleep(80);

        assertThat(limiter.isBlocked("user@test.com")).isFalse();
    }

    @Test
    @DisplayName("secondsUntilUnblock retorna 0 quando a chave não existe")
    void secondsUntilUnblock_unknownKey_returnsZero() {
        assertThat(limiter.secondsUntilUnblock("ninguem@test.com")).isEqualTo(0);
    }

    @Test
    @DisplayName("secondsUntilUnblock retorna valor positivo enquanto o bloqueio está ativo")
    void secondsUntilUnblock_whileBlocked_returnsPositive() {
        limiter.registerFailure("user@test.com");
        limiter.registerFailure("user@test.com");
        limiter.registerFailure("user@test.com");
        assertThat(limiter.isBlocked("user@test.com")).isTrue();

        assertThat(limiter.secondsUntilUnblock("user@test.com")).isGreaterThan(0);
    }

    @Test
    @DisplayName("secondsUntilUnblock retorna 0 após a janela expirar")
    void secondsUntilUnblock_afterWindowExpires_returnsZero() throws InterruptedException {
        ReflectionTestUtils.setField(limiter, "window", Duration.ofMillis(50));
        limiter.registerFailure("user@test.com");
        limiter.registerFailure("user@test.com");
        limiter.registerFailure("user@test.com");

        Thread.sleep(80);

        assertThat(limiter.secondsUntilUnblock("user@test.com")).isEqualTo(0);
    }
}
