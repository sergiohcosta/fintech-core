# Security Hardening — Issues #110–#115 Implementation Plan

> **Status: CONCLUÍDO — 2026-06-16**

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Corrigir 6 problemas de segurança identificados em auditoria: controle de acesso ausente em `PATCH /api/tenant/settings` (#110), enumeração de usuários no login (#111), ausência de política de senha mínima (#112), ausência de rate limiting no login (#113), bug de timezone na expiração do JWT (#114), e `User.isEnabled()` ignorando o campo `active` (#115). **#116 (avaliação de Row-Level Security) fica fora de escopo desta sessão** — decisão do usuário, será tratada depois.

**Architecture:** A maioria das issues é independente (#114, #110, #112 tocam arquivos isolados). #111, #115 e #113 convergem todas em `AuthController.login()` — serão implementadas em sequência incremental (rate limiter primeiro como peça isolada, depois o método `login()` reescrito uma única vez) para evitar reescrever o mesmo método várias vezes. `SecurityFilter` (validação de token por request) recebe a segunda metade de #115. Rate limiting é em memória (`ConcurrentHashMap`, sem Redis/biblioteca nova) — decisão do usuário dado que a issue é baixa prioridade e não há exploração ativa.

**Tech Stack:** Java 21 / Spring Boot 4 (backend, issues #110, #111, #113, #114, #115 + parte backend de #112), Angular 21 Zoneless + Angular Material 3 (frontend, parte de #112).

---

## Mapa de Arquivos

| Arquivo | Mudança |
|---------|---------|
| `backend/.../config/TokenService.java` | `genExpirationDate()` usa `Instant.now()` em vez de `LocalDateTime.now()` + timezone fixo |
| `backend/.../config/TokenServiceTest.java` | Teste: expiração ~2h independente do timezone default da JVM |
| `backend/.../config/SecurityConfigurations.java` | Adiciona `PATCH /api/tenant/settings` → `hasRole("ADMIN")` |
| `backend/.../controller/TenantControllerTest.java` | **Novo** — 204 para ADMIN, 403 para USER e para não-autenticado |
| `backend/.../config/LoginRateLimiter.java` | **Novo** — limitador em memória por chave (email), janela deslizante |
| `backend/.../config/LoginRateLimiterTest.java` | **Novo** — testes unitários do limitador |
| `backend/.../domain/user/User.java` | `isEnabled()` retorna `this.active` em vez de `true` hardcoded |
| `backend/.../controller/AuthController.java` | `login()` reescrito: resposta genérica, checa `isEnabled()`, usa `LoginRateLimiter` |
| `backend/.../controller/AuthControllerTest.java` | Atualiza testes existentes (senha fraca quebraria) + novos: usuário não encontrado, usuário inativo, rate limit, senha fraca no registro/convite |
| `backend/.../config/SecurityFilter.java` | Não autentica requisição se `userDetails.isEnabled() == false` |
| `backend/.../config/SecurityFilterTest.java` | **Novo** — testes unitários do filtro (usuário ativo vs inativo) |
| `backend/.../dto/TenantRegistrationDTO.java` | `password` ganha `@Pattern` (mín. 8, maiúscula+minúscula+número) |
| `backend/.../dto/AcceptInviteDTO.java` | Idem |
| `api-spec/openapi.yaml` | `TenantRegistrationDTO.password` documenta `minLength`/`pattern` |
| `backend/src/main/resources/static/openapi.yaml` | Cópia do spec atualizado |
| `frontend/.../auth/register/register.ts` | `Validators.minLength(8)` + `Validators.pattern(...)` no campo `password` |
| `frontend/.../auth/register/register.html` | `mat-hint`/`mat-error` para a nova regra de senha |
| `frontend/.../auth/accept-invite/accept-invite.ts` | Idem `register.ts` |
| `frontend/.../auth/accept-invite/accept-invite.html` | Idem `register.html` |
| `frontend/.../auth/accept-invite/accept-invite.spec.ts` | Senha de teste `senha123` → `Senha123` (passa a satisfazer a nova regra) |
| `docs/http/seed-dataset.http` | Senha `costa123` → `Costa123` nas 4 ocorrências (register/login/accept-invite) |
| `summary.md` | Seção Segurança documenta as novas regras (401 genérico, rate limit, `isEnabled`, política de senha) |

---

## Task 1: Corrige bug de timezone na expiração do JWT (#114)

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/config/TokenService.java`
- Modify: `backend/src/test/java/com/fintech/api/config/TokenServiceTest.java`

- [ ] **Step 1: Escrever o teste que falha**

Em `TokenServiceTest.java`, adicionar os imports:

```java
import java.time.Instant;
import java.util.TimeZone;
```

E o teste (após `generateToken_emitsUserRole`):

```java
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
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

```bash
cd /home/sergio/fintech-core/backend && ./mvnw test -Dtest=TokenServiceTest -q
```

Esperado: FAIL — com timezone default UTC, o bug atual soma +3h extras (`ZoneOffset.of("-03:00")` aplicado sobre um `LocalDateTime` já em UTC), então `diffSeconds` será ~10800, não < 5.

- [ ] **Step 3: Corrigir `TokenService.java`**

Substituir o método (linhas 37-39) e remover os imports não usados:

```java
    private Instant genExpirationDate() {
        return Instant.now().plusSeconds(2 * 3600);
    }
```

Remover do topo do arquivo:
```java
import java.time.LocalDateTime;
import java.time.ZoneOffset;
```//manter apenas `import java.time.Instant;`

- [ ] **Step 4: Rodar o teste e confirmar que passa**

```bash
cd /home/sergio/fintech-core/backend && ./mvnw test -Dtest=TokenServiceTest -q
```

Esperado: BUILD SUCCESS, todos os testes de `TokenServiceTest` passam.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/config/TokenService.java \
        backend/src/test/java/com/fintech/api/config/TokenServiceTest.java
git commit -m "fix(auth): corrige expiração do JWT calculada com timezone errado (#114)"
```

---

## Task 2: Restringe `PATCH /api/tenant/settings` a ADMIN (#110)

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/config/SecurityConfigurations.java`
- Create: `backend/src/test/java/com/fintech/api/controller/TenantControllerTest.java`

- [ ] **Step 1: Escrever o teste que falha**

Criar `backend/src/test/java/com/fintech/api/controller/TenantControllerTest.java`:

```java
package com.fintech.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.api.config.SecurityConfigurations;
import com.fintech.api.config.SecurityFilter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.TenantSettingsPatchRequest;
import com.fintech.api.repository.TenantRepository;
import com.fintech.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import({ SecurityConfigurations.class, SecurityFilter.class })
class TenantControllerTest {

    private MockMvc mockMvc;

    @Autowired WebApplicationContext context;
    @MockitoBean TenantRepository tenantRepository;
    @MockitoBean UserRepository userRepository;
    @MockitoBean TokenService tokenService;

    private final ObjectMapper mapper = new ObjectMapper();
    private User authUser;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());

        authUser = new User();
        authUser.setEmail("admin@test.com");
        authUser.setRole(UserRole.ADMIN);
        authUser.setTenant(tenant);

        when(tokenService.validateToken(anyString())).thenReturn(authUser.getEmail());
        when(userRepository.findByEmail(authUser.getEmail())).thenReturn(Optional.of(authUser));
        when(tenantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("PATCH /api/tenant/settings retorna 204 para ADMIN")
    void patchSettings_withAdminRole_returnsNoContent() throws Exception {
        TenantSettingsPatchRequest req = new TenantSettingsPatchRequest(15);

        mockMvc.perform(patch("/api/tenant/settings")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /api/tenant/settings retorna 403 para role USER")
    void patchSettings_withUserRole_returns403() throws Exception {
        authUser.setRole(UserRole.USER);
        TenantSettingsPatchRequest req = new TenantSettingsPatchRequest(15);

        mockMvc.perform(patch("/api/tenant/settings")
                        .header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /api/tenant/settings retorna 403 sem autenticação")
    void patchSettings_withoutAuth_returns403() throws Exception {
        TenantSettingsPatchRequest req = new TenantSettingsPatchRequest(15);

        mockMvc.perform(patch("/api/tenant/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que `patchSettings_withUserRole_returns403` falha**

```bash
cd /home/sergio/fintech-core/backend && ./mvnw test -Dtest=TenantControllerTest -q
```

Esperado: FAIL nesse teste específico — hoje o endpoint cai em `anyRequest().authenticated()`, então role USER recebe 204 em vez de 403.

- [ ] **Step 3: Corrigir `SecurityConfigurations.java`**

Adicionar a linha (após `.requestMatchers(HttpMethod.GET, "/api/members").hasRole("ADMIN")` e antes de `.anyRequest().authenticated()`):

```java
                        .requestMatchers(HttpMethod.GET, "/api/members").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/tenant/settings").hasRole("ADMIN")
                        .anyRequest().authenticated())
```

- [ ] **Step 4: Rodar o teste e confirmar que passa**

```bash
cd /home/sergio/fintech-core/backend && ./mvnw test -Dtest=TenantControllerTest -q
```

Esperado: BUILD SUCCESS, os 3 testes passam.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/config/SecurityConfigurations.java \
        backend/src/test/java/com/fintech/api/controller/TenantControllerTest.java
git commit -m "fix(security): restringe PATCH /api/tenant/settings a ADMIN (#110)"
```

> **Nota:** o critério "frontend oculta a opção para não-admins" da issue #110 não se aplica ainda — não existe nenhuma tela frontend que consome `PATCH /api/tenant/settings` hoje (só o client gerado pelo Orval). Quando essa tela for criada, ela deve seguir o padrão de [[feedback_access_control_depth]] (`@if (isAdmin())`).

---

## Task 3: Cria `LoginRateLimiter` em memória (base para #113)

**Files:**
- Create: `backend/src/main/java/com/fintech/api/config/LoginRateLimiter.java`
- Create: `backend/src/test/java/com/fintech/api/config/LoginRateLimiterTest.java`

- [ ] **Step 1: Escrever os testes que falham**

Criar `backend/src/test/java/com/fintech/api/config/LoginRateLimiterTest.java`:

```java
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
}
```

- [ ] **Step 2: Rodar os testes e confirmar que falham (classe não existe)**

```bash
cd /home/sergio/fintech-core/backend && ./mvnw test -Dtest=LoginRateLimiterTest -q
```

Esperado: FAIL — `LoginRateLimiter` não existe ainda (erro de compilação).

- [ ] **Step 3: Implementar `LoginRateLimiter.java`**

Criar `backend/src/main/java/com/fintech/api/config/LoginRateLimiter.java`:

```java
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
```

- [ ] **Step 4: Rodar os testes e confirmar que passam**

```bash
cd /home/sergio/fintech-core/backend && ./mvnw test -Dtest=LoginRateLimiterTest -q
```

Esperado: BUILD SUCCESS, os 5 testes passam.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/config/LoginRateLimiter.java \
        backend/src/test/java/com/fintech/api/config/LoginRateLimiterTest.java
git commit -m "feat(auth): adiciona LoginRateLimiter em memória (base para #113)"
```

---

## Task 4: Corrige enumeração de usuários + checa usuário ativo + aplica rate limit no login (#111, #115 parte 1, #113)

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/domain/user/User.java`
- Modify: `backend/src/main/java/com/fintech/api/controller/AuthController.java`
- Modify: `backend/src/test/java/com/fintech/api/controller/AuthControllerTest.java`

- [ ] **Step 1: Escrever os testes que falham em `AuthControllerTest.java`**

Adicionar o import:
```java
import com.fintech.api.config.LoginRateLimiter;
```

Adicionar o mock (junto aos outros `@MockitoBean`):
```java
    @MockitoBean
    private LoginRateLimiter loginRateLimiter;
```

Adicionar os testes (após `shouldFailLogin`):

```java
    @Test
    @DisplayName("Should return generic 401 when user does not exist (no enumeration)")
    void shouldFailLoginWhenUserNotFound() throws Exception {
        LoginDTO loginDTO = new LoginDTO("naoexiste@test.com", "qualquer");
        when(userRepository.findByEmail("naoexiste@test.com")).thenReturn(Optional.empty());

        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginDTO)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should return 401 when user is inactive, even with correct password")
    void shouldFailLoginWhenUserInactive() throws Exception {
        LoginDTO loginDTO = new LoginDTO("inativo@test.com", "password");
        User user = new User();
        user.setEmail("inativo@test.com");
        user.setPasswordHash("encoded_password");
        user.setActive(false);

        when(userRepository.findByEmail("inativo@test.com")).thenReturn(Optional.of(user));

        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginDTO)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should return 429 when rate limit exceeded for the email")
    void shouldReturn429WhenRateLimited() throws Exception {
        LoginDTO loginDTO = new LoginDTO("test@email.com", "password");
        when(loginRateLimiter.isBlocked("test@email.com")).thenReturn(true);

        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginDTO)))
                .andExpect(status().isTooManyRequests());
    }
```

- [ ] **Step 2: Rodar os testes e confirmar que falham**

```bash
cd /home/sergio/fintech-core/backend && ./mvnw test -Dtest=AuthControllerTest -q
```

Esperado: FAIL em `shouldFailLoginWhenUserNotFound` (hoje retorna 400, lança `IllegalArgumentException`), `shouldFailLoginWhenUserInactive` (hoje retorna 200 — `isEnabled()` sempre `true`) e `shouldReturn429WhenRateLimited` (limiter ainda não é usado pelo controller).

- [ ] **Step 3: Corrigir `User.isEnabled()`**

Em `User.java`, substituir (linhas 92-95):

```java
    @Override
    public boolean isEnabled() {
        return this.active;
    }
```

- [ ] **Step 4: Reescrever `AuthController.login()`**

Adicionar o campo (junto aos outros, `@RequiredArgsConstructor` gera o construtor automaticamente):

```java
    private final LoginRateLimiter loginRateLimiter;
```

Adicionar o import:
```java
import com.fintech.api.config.LoginRateLimiter;
```

Substituir o método `login()` (linhas 40-52):

```java
    @Override
    @PostMapping("/login")
    public ResponseEntity<LoginResponseDTO> login(@RequestBody @Valid LoginDTO data) {
        if (loginRateLimiter.isBlocked(data.email())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        var userOpt = this.userRepository.findByEmail(data.email());
        boolean authenticated = userOpt.isPresent()
                && userOpt.get().isEnabled()
                && passwordEncoder.matches(data.password(), userOpt.get().getPasswordHash());

        if (!authenticated) {
            loginRateLimiter.registerFailure(data.email());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        loginRateLimiter.registerSuccess(data.email());
        String token = tokenService.generateToken(userOpt.get());
        return ResponseEntity.ok(new LoginResponseDTO(token));
    }
```

- [ ] **Step 5: Rodar os testes e confirmar que passam**

```bash
cd /home/sergio/fintech-core/backend && ./mvnw test -Dtest=AuthControllerTest -q
```

Esperado: BUILD SUCCESS, todos os testes (antigos + novos) passam.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/fintech/api/domain/user/User.java \
        backend/src/main/java/com/fintech/api/controller/AuthController.java \
        backend/src/test/java/com/fintech/api/controller/AuthControllerTest.java
git commit -m "fix(auth): elimina enumeração de usuários, checa isEnabled() e aplica rate limit no login (#111, #113, #115)"
```

---

## Task 5: `SecurityFilter` rejeita usuário inativo na validação do token (#115 parte 2)

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/config/SecurityFilter.java`
- Create: `backend/src/test/java/com/fintech/api/config/SecurityFilterTest.java`

- [ ] **Step 1: Escrever os testes que falham**

Criar `backend/src/test/java/com/fintech/api/config/SecurityFilterTest.java`:

```java
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
```

- [ ] **Step 2: Rodar os testes e confirmar que `doFilterInternal_inactiveUser_doesNotSetAuthentication` falha**

```bash
cd /home/sergio/fintech-core/backend && ./mvnw test -Dtest=SecurityFilterTest -q
```

Esperado: FAIL — hoje o filtro autentica qualquer usuário encontrado, independente de `isEnabled()`.

- [ ] **Step 3: Corrigir `SecurityFilter.java`**

Substituir o trecho (linhas 41-55):

```java
            } else {
                UserDetails userDetails = userRepository.findByEmail(email).orElse(null);
                if (userDetails == null) {
                    log.warn("Token válido mas usuário não encontrado [email={}]", email);
                } else if (!userDetails.isEnabled()) {
                    log.warn("Token válido mas usuário está inativo [email={}]", email);
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
```

- [ ] **Step 4: Rodar os testes e confirmar que passam**

```bash
cd /home/sergio/fintech-core/backend && ./mvnw test -Dtest=SecurityFilterTest -q
```

Esperado: BUILD SUCCESS, os 2 testes passam.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/config/SecurityFilter.java \
        backend/src/test/java/com/fintech/api/config/SecurityFilterTest.java
git commit -m "fix(auth): SecurityFilter não autentica requisição de usuário inativo (#115)"
```

---

## Task 6: Política de senha mínima — backend (#112)

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/dto/TenantRegistrationDTO.java`
- Modify: `backend/src/main/java/com/fintech/api/dto/AcceptInviteDTO.java`
- Modify: `backend/src/test/java/com/fintech/api/controller/AuthControllerTest.java`
- Modify: `api-spec/openapi.yaml`
- Modify: `backend/src/main/resources/static/openapi.yaml`

- [ ] **Step 1: Atualizar testes existentes que usam senha fraca + adicionar novos testes que falham**

Em `AuthControllerTest.java`, `shouldRegisterTenant` usa senha `"123456"` — trocar por uma senha que vai continuar válida após a mudança:

```java
        TenantRegistrationDTO dto = new TenantRegistrationDTO(
                "My Tenant", "Admin", "admin@email.com", "Senha123");
```

`shouldAcceptInviteSuccessfully` usa senha `"senha123"` (sem maiúscula) — trocar:

```java
        AcceptInviteDTO dto = new AcceptInviteDTO("valid-token", "João Silva", "Senha123");
```

Adicionar os novos testes (após `shouldRegisterTenant` e após `shouldAcceptInviteSuccessfully`, respectivamente):

```java
    @Test
    @DisplayName("POST /auth/register retorna 400 quando senha não atende a política mínima")
    void shouldFailRegisterWithWeakPassword() throws Exception {
        TenantRegistrationDTO dto = new TenantRegistrationDTO(
                "My Tenant", "Admin", "admin@email.com", "12345678");

        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }
```

```java
    @Test
    @DisplayName("POST /auth/accept-invite retorna 400 quando senha não atende a política mínima")
    void shouldFailAcceptInviteWithWeakPassword() throws Exception {
        AcceptInviteDTO dto = new AcceptInviteDTO("valid-token", "João Silva", "12345678");

        mockMvc.perform(post("/auth/accept-invite")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 2: Rodar os testes e confirmar que os 2 novos falham**

```bash
cd /home/sergio/fintech-core/backend && ./mvnw test -Dtest=AuthControllerTest -q
```

Esperado: FAIL em `shouldFailRegisterWithWeakPassword` e `shouldFailAcceptInviteWithWeakPassword` (hoje qualquer senha não-vazia passa).

- [ ] **Step 3: Adicionar `@Pattern` em `TenantRegistrationDTO.java`**

```java
package com.fintech.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// Olha como é limpo. Não precisa de 'class', nem getters/setters.
// Já colocamos validações aqui (Bean Validation)
public record TenantRegistrationDTO(
                @NotBlank(message = "Nome é obrigatório") String name,

                @NotBlank(message = "Nome do administrador é obrigatório") String adminName,

                @NotBlank(message = "Email é obrigatório") @Email(message = "Email inválido") String adminEmail,

                @NotBlank(message = "Senha é obrigatória")
                @Pattern(
                    regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$",
                    message = "Senha deve ter no mínimo 8 caracteres, incluindo letra maiúscula, minúscula e número"
                )
                String password) {
}
```

- [ ] **Step 4: Adicionar `@Pattern` em `AcceptInviteDTO.java`**

```java
package com.fintech.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AcceptInviteDTO(
    @NotBlank(message = "Token é obrigatório")   String token,
    @NotBlank(message = "Nome é obrigatório")    String name,
    @NotBlank(message = "Senha é obrigatória")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$",
        message = "Senha deve ter no mínimo 8 caracteres, incluindo letra maiúscula, minúscula e número"
    )
    String password
) {}
```

- [ ] **Step 5: Rodar os testes e confirmar que todos passam**

```bash
cd /home/sergio/fintech-core/backend && ./mvnw test -Dtest=AuthControllerTest -q
```

Esperado: BUILD SUCCESS, todos os testes (antigos atualizados + novos) passam.

- [ ] **Step 6: Documentar a regra em `api-spec/openapi.yaml`**

Localizar o schema `TenantRegistrationDTO` (~linha 58-73) e substituir o campo `password`:

```yaml
        password:
          type: string
          minLength: 8
          pattern: '^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$'
          description: "Mínimo 8 caracteres, com letra maiúscula, minúscula e número"
```

- [ ] **Step 7: Replicar a mesma mudança em `backend/src/main/resources/static/openapi.yaml`**

Localizar o mesmo bloco `TenantRegistrationDTO` e aplicar a substituição idêntica.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/fintech/api/dto/TenantRegistrationDTO.java \
        backend/src/main/java/com/fintech/api/dto/AcceptInviteDTO.java \
        backend/src/test/java/com/fintech/api/controller/AuthControllerTest.java \
        api-spec/openapi.yaml \
        backend/src/main/resources/static/openapi.yaml
git commit -m "fix(auth): exige senha mínima de 8 caracteres com maiúscula/minúscula/número (#112)"
```

---

## Task 7: Política de senha mínima — frontend (#112)

**Files:**
- Modify: `frontend/src/app/features/auth/register/register.ts`
- Modify: `frontend/src/app/features/auth/register/register.html`
- Modify: `frontend/src/app/features/auth/accept-invite/accept-invite.ts`
- Modify: `frontend/src/app/features/auth/accept-invite/accept-invite.html`
- Modify: `frontend/src/app/features/auth/accept-invite/accept-invite.spec.ts`

- [ ] **Step 1: Atualizar o teste que vai falhar em `accept-invite.spec.ts`**

Trocar as duas ocorrências de `password: 'senha123'` (linhas 75 e 91) por:

```ts
    component.form.setValue({ name: 'João', password: 'Senha123' });
```

- [ ] **Step 2: Rodar os testes do componente e confirmar comportamento atual**

```bash
cd /home/sergio/fintech-core/frontend && npx vitest run src/app/features/auth/accept-invite/accept-invite.spec.ts
```

Esperado: PASS — o validator novo ainda não existe, então `'Senha123'` e `'senha123'` se comportam igual por ora (este passo apenas confirma a baseline antes da mudança de validators).

- [ ] **Step 3: Atualizar `register.ts`**

Substituir o `form` (linhas 45-50):

```ts
  form = this.fb.group({
    name: ['', [Validators.required]],
    adminName: ['', [Validators.required]],
    adminEmail: ['', [Validators.required, Validators.email]],
    password: ['', [
      Validators.required,
      Validators.minLength(8),
      Validators.pattern(/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).+$/),
    ]]
  });
```

- [ ] **Step 4: Atualizar `register.html`**

Substituir o bloco do campo de senha (linhas 32-35):

```html
                <mat-form-field appearance="outline" class="full-width">
                    <mat-label>Senha</mat-label>
                    <input matInput type="password" formControlName="password">
                    <mat-hint>Mínimo 8 caracteres, com letra maiúscula, minúscula e número</mat-hint>
                    <mat-error>Senha deve ter no mínimo 8 caracteres, com letra maiúscula, minúscula e número</mat-error>
                </mat-form-field>
```

- [ ] **Step 5: Atualizar `accept-invite.ts`**

Substituir o `form` (linhas 41-44):

```ts
  form = this.fb.group({
    name:     ['', [Validators.required]],
    password: ['', [
      Validators.required,
      Validators.minLength(8),
      Validators.pattern(/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).+$/),
    ]],
  });
```

- [ ] **Step 6: Atualizar `accept-invite.html`**

Substituir o bloco do campo de senha (linhas 42-45):

```html
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Senha</mat-label>
            <input matInput type="password" formControlName="password">
            <mat-hint>Mínimo 8 caracteres, com letra maiúscula, minúscula e número</mat-hint>
            <mat-error>Senha deve ter no mínimo 8 caracteres, com letra maiúscula, minúscula e número</mat-error>
          </mat-form-field>
```

- [ ] **Step 7: Rodar os testes do componente e confirmar que ainda passam**

```bash
cd /home/sergio/fintech-core/frontend && npx vitest run src/app/features/auth/accept-invite/accept-invite.spec.ts src/app/features/auth/register/register.spec.ts
```

Esperado: PASS — `'Senha123'` satisfaz `minLength(8)` + o pattern; se o Step 1 não tivesse trocado a senha de teste, `onSubmit()` teria retornado antes de chamar `acceptInvite` (form inválido) e os testes que verificam `setToken`/`navigate` teriam falhado.

- [ ] **Step 8: Verificar compilação TypeScript**

```bash
cd /home/sergio/fintech-core/frontend && npx tsc --noEmit 2>&1 | head -30
```

Esperado: sem erros.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/app/features/auth/register/register.ts \
        frontend/src/app/features/auth/register/register.html \
        frontend/src/app/features/auth/accept-invite/accept-invite.ts \
        frontend/src/app/features/auth/accept-invite/accept-invite.html \
        frontend/src/app/features/auth/accept-invite/accept-invite.spec.ts
git commit -m "fix(auth): reflete política de senha mínima nos formulários de registro e convite (#112)"
```

---

## Task 8: Atualiza dataset de teste para a nova política de senha

**Files:**
- Modify: `docs/http/seed-dataset.http`

**Contexto:** `docs/http/seed-dataset.http` usa a senha `costa123` (sem maiúscula) em 4 chamadas (`register`, `login` subsequente, e 2x `accept-invite`). Com a regra da Task 6, essas chamadas passariam a retornar 400. Por convenção do projeto (dataset é artefato vivo — [[feedback_dataset_maintenance]]), atualizar para uma senha que cumpre a nova política.

- [ ] **Step 1: Substituir todas as ocorrências**

```bash
sed -i 's/"costa123"/"Costa123"/g' /home/sergio/fintech-core/docs/http/seed-dataset.http
```

- [ ] **Step 2: Confirmar que as 4 ocorrências foram trocadas**

```bash
grep -n "costa123\|Costa123" /home/sergio/fintech-core/docs/http/seed-dataset.http
```

Esperado: 4 ocorrências de `Costa123`, nenhuma de `costa123`.

- [ ] **Step 3: Commit**

```bash
git add docs/http/seed-dataset.http
git commit -m "docs: atualiza senha de teste em seed-dataset.http para a nova política mínima"
```

---

## Task 9: Verificação completa + documentação

**Files:**
- Modify: `summary.md`

- [ ] **Step 1: Rodar a suíte completa do backend**

```bash
cd /home/sergio/fintech-core/backend && ./mvnw test -q
```

Esperado: BUILD SUCCESS, nenhum teste quebrado em outras classes (em especial `InvitationControllerTest`, que também usa `SecurityConfigurations`/`SecurityFilter` reais).

- [ ] **Step 2: Rodar a suíte completa do frontend**

```bash
cd /home/sergio/fintech-core/frontend && npx tsc --noEmit && npx vitest run
```

Esperado: sem erros de tipo, todos os testes passam.

- [ ] **Step 3: Atualizar a seção Segurança de `summary.md`**

Adicionar ao final da seção `## Segurança` (após a linha do `SecurityFilter`):

```markdown

**Login (`/auth/login`):** resposta genérica (401) tanto para email inexistente quanto para senha incorreta ou usuário inativo (sem enumeração de usuários). Rate limit em memória: 5 tentativas falhas por email a cada 1 minuto → `429 Too Many Requests` (`LoginRateLimiter`). `User.isEnabled()` reflete o campo `active` — checado no login e em toda requisição autenticada (`SecurityFilter`).

**Política de senha (registro e aceite de convite):** mínimo 8 caracteres, com letra maiúscula, minúscula e número (`TenantRegistrationDTO`, `AcceptInviteDTO`).
```

- [ ] **Step 4: Marcar o plano como concluído**

No topo deste arquivo (`docs/superpowers/plans/2026-06-16-security-hardening-110-115.md`), adicionar após o título:

```markdown
> **Status: CONCLUÍDO — 2026-06-16**
```

- [ ] **Step 5: Commit**

```bash
git add summary.md docs/superpowers/plans/2026-06-16-security-hardening-110-115.md
git commit -m "docs: documenta hardening de autenticação e marca plano #110-115 como concluído"
```

---

## Task 10: Finalizar a branch

- [ ] Usar a skill `superpowers:finishing-a-development-branch` para decidir merge em `develop` + push + abertura da PR cumulativa para `main` (issues #110, #111, #112, #113, #114, #115 numa única PR, por [[feedback_pr_batching]] / `git-operator.md`).
- [ ] Fechar as issues #110, #111, #112, #113, #114, #115 no GitHub referenciando a PR (não fechar #116 — foi adiada).

---

## Self-Review

**Spec coverage:**
- #110 (PATCH /api/tenant/settings sem restrição ADMIN): Task 2 — coberto ✓ (frontend N/A, documentado)
- #111 (enumeração de usuários no login): Task 4 — coberto ✓
- #112 (sem política de senha mínima): Tasks 6 (backend) e 7 (frontend) — coberto ✓
- #113 (sem rate limiting no login): Tasks 3 (limiter) e 4 (wiring) — coberto ✓
- #114 (bug de timezone na expiração do JWT): Task 1 — coberto ✓
- #115 (`isEnabled()` ignora `active`): Task 4 (User + AuthController) e Task 5 (SecurityFilter) — coberto ✓
- #116 (avaliação de RLS): **fora de escopo**, adiada por decisão do usuário — nenhuma task

**Verificações de consistência:**
- `LoginRateLimiter` criado na Task 3 com `isBlocked`/`registerFailure`/`registerSuccess`; usado com a mesma assinatura na Task 4 — consistente ✓
- `User.setActive(false)` (Lombok `@Data` em campo `boolean active`) usado nos testes das Tasks 4 e 5 — consistente com o campo já existente em `User.java:48` ✓
- Regex de senha (`^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$`) idêntica em `TenantRegistrationDTO`, `AcceptInviteDTO` (Task 6), `openapi.yaml` (Task 6) e nos validators do frontend (Task 7) — consistente ✓
- Senha de teste em `AuthControllerTest` (Task 6: `"Senha123"`), `accept-invite.spec.ts` (Task 7: `'Senha123'`) e `seed-dataset.http` (Task 8: `"Costa123"`) todas satisfazem a nova regra — consistente ✓
- `TenantControllerTest` (Task 2) segue exatamente o padrão de `InvitationControllerTest` (`@Import({SecurityConfigurations.class, SecurityFilter.class})` + `springSecurity()` + mocks de `TokenService`/`UserRepository`) — consistente com a convenção já estabelecida no projeto ✓
