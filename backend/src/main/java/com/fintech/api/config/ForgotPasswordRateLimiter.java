package com.fintech.api.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

// Mesma lógica de LoginRateLimiter, instância própria (mapa e janela separados) — misturar
// contador com o login faria uma tentativa de login falha consumir o teto de forgot-password
// e vice-versa, dois fluxos que não deveriam competir pelo mesmo orçamento de tentativas.
@Component
public class ForgotPasswordRateLimiter {

    @Value("${security.rate-limit.forgot-password.max-attempts:5}")
    private int maxAttempts;

    @Value("${security.rate-limit.forgot-password.window-seconds:60}")
    private int windowSeconds;

    @Value("${security.rate-limit.forgot-password.max-keys:100000}")
    private int maxKeys;

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
            windows.remove(k, w);
            return false;
        }
        return w.attempts() >= maxAttempts;
    }

    public void registerFailure(String key) {
        String k = normalize(key);
        Instant now = Instant.now();
        if (windows.size() >= maxKeys && !windows.containsKey(k)) {
            evictExpired(now);
            if (windows.size() >= maxKeys) return;
        }
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

    public long secondsUntilUnblock(String key) {
        RateLimitWindow w = windows.get(normalize(key));
        if (w == null) return 0;
        long remaining = Duration.between(Instant.now(), w.startedAt().plus(window)).getSeconds();
        return Math.max(0, remaining);
    }

    private void evictExpired(Instant now) {
        windows.entrySet().removeIf(e -> now.isAfter(e.getValue().startedAt().plus(window)));
    }

    @Scheduled(fixedDelayString = "${security.rate-limit.forgot-password.sweep-ms:60000}")
    void sweepExpired() {
        evictExpired(Instant.now());
    }

    private String normalize(String key) {
        return key.toLowerCase();
    }
}
