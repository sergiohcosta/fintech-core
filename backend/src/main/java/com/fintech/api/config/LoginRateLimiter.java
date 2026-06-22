package com.fintech.api.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

// ponytail: top-level para evitar LoginRateLimiter$Window — DevTools RestartClassLoader não encontra inner classes
record RateLimitWindow(int attempts, Instant startedAt) {}

@Component
public class LoginRateLimiter {

    @Value("${security.rate-limit.max-attempts:5}")
    private int maxAttempts;

    @Value("${security.rate-limit.window-seconds:60}")
    private int windowSeconds;

    // Calculado em @PostConstruct: @Value não aceita Duration diretamente.
    // Campo separado preserva ReflectionTestUtils.setField("window", ...) nos testes unitários.
    private Duration window;

    @PostConstruct
    void init() {
        window = Duration.ofSeconds(windowSeconds);
    }

    private final ConcurrentHashMap<String, RateLimitWindow> windows = new ConcurrentHashMap<>();

    public boolean isBlocked(String key) {
        String k = normalize(key);
        RateLimitWindow w = windows.get(k);
        if (w == null) return false;
        if (Instant.now().isAfter(w.startedAt().plus(window))) {
            // Lazy eviction com remove condicional por instância — evita apagar uma janela
            // recém-criada por outro thread entre o get() e o remove() (TOCTOU).
            windows.remove(k, w);
            return false;
        }
        return w.attempts() >= maxAttempts;
    }

    public void registerFailure(String key) {
        String k = normalize(key);
        Instant now = Instant.now();
        windows.compute(k, (ignored, current) -> {
            if (current == null || now.isAfter(current.startedAt().plus(window))) {
                return new RateLimitWindow(1, now);
            }
            return new RateLimitWindow(current.attempts() + 1, current.startedAt());
        });
    }

    public void registerSuccess(String key) {
        windows.remove(normalize(key));
    }

    // Retorna quantos segundos faltam para o bloqueio expirar (0 se não bloqueado ou já expirado).
    // Usado pelo AuthController para popular o header Retry-After (RFC 6585).
    public long secondsUntilUnblock(String key) {
        RateLimitWindow w = windows.get(normalize(key));
        if (w == null) return 0;
        long remaining = Duration.between(Instant.now(), w.startedAt().plus(window)).getSeconds();
        return Math.max(0, remaining);
    }

    private String normalize(String key) {
        return key.toLowerCase();
    }
}
