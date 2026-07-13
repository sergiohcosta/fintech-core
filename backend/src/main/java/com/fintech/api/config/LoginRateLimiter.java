package com.fintech.api.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
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

    // #144: teto rígido do mapa. Sem isso, um flood de emails aleatórios cresce o
    // ConcurrentHashMap sem limite (DoS de memória). Bounded a O(maxKeys).
    @Value("${security.rate-limit.max-keys:100000}")
    private int maxKeys;

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
        // #144: teto de memória. Se o mapa está cheio e a chave é nova, tenta liberar espaço
        // removendo janelas expiradas; se ainda estiver cheio, ignora a nova chave (fail-open —
        // a checagem de credencial continua e atacantes já rastreados seguem bloqueados).
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

    // Retorna quantos segundos faltam para o bloqueio expirar (0 se não bloqueado ou já expirado).
    // Usado pelo AuthController para popular o header Retry-After (RFC 6585).
    public long secondsUntilUnblock(String key) {
        RateLimitWindow w = windows.get(normalize(key));
        if (w == null) return 0;
        long remaining = Duration.between(Instant.now(), w.startedAt().plus(window)).getSeconds();
        return Math.max(0, remaining);
    }

    // #144: remove janelas já expiradas. Usado pelo sweep agendado e pela guarda de teto.
    // removeIf sobre entrySet de ConcurrentHashMap é seguro (iterador weakly-consistent).
    private void evictExpired(Instant now) {
        windows.entrySet().removeIf(e -> now.isAfter(e.getValue().startedAt().plus(window)));
    }

    // Varredura periódica: sem ela, a eviction preguiçosa só limpa uma chave quando ela é relida,
    // deixando janelas expiradas acumuladas. fixedDelay evita sobreposição de execuções.
    @Scheduled(fixedDelayString = "${security.rate-limit.sweep-ms:60000}")
    void sweepExpired() {
        evictExpired(Instant.now());
    }

    private String normalize(String key) {
        return key.toLowerCase();
    }
}
