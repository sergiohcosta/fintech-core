package com.fintech.api.config;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginRateLimiter {

    private int maxAttempts = 5;
    private Duration window = Duration.ofMinutes(1);

    private record Window(int attempts, Instant startedAt) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public boolean isBlocked(String key) {
        Window w = windows.get(normalize(key));
        if (w == null) return false;
        if (Instant.now().isAfter(w.startedAt().plus(window))) return false;
        return w.attempts() >= maxAttempts;
    }

    public void registerFailure(String key) {
        String k = normalize(key);
        Instant now = Instant.now();
        windows.compute(k, (ignored, current) -> {
            if (current == null || now.isAfter(current.startedAt().plus(window))) {
                return new Window(1, now);
            }
            return new Window(current.attempts() + 1, current.startedAt());
        });
    }

    public void registerSuccess(String key) {
        windows.remove(normalize(key));
    }

    private String normalize(String key) {
        return key.toLowerCase();
    }
}
