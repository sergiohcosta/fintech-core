# Recuperação de senha — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** fluxo público de recuperação de senha (`forgot-password` + `reset-password`), com
envio de email via SMTP e revogação de sessões JWT ativas ao trocar a senha. Spec:
`docs/superpowers/specs/2026-09-04-recuperacao-de-senha-design.md`.

**Tech Stack:** Java 21, Spring Boot, Spring Mail, Flyway, Angular 21 Zoneless/Signals.

## Global Constraints

- Próxima migration livre: **V37**.
- Spec-first: `api-spec/openapi.yaml` antes de qualquer código, depois `./scripts/api-sync.sh`.
- SemVer: **MINOR** (endpoints novos, aditivos).
- Baseline verde antes de começar (rodar suíte na worktree, Task 0).
- `password_reset_tokens` **fora do rollout de RLS** (decisão registrada na spec, seção 2) —
  não adicionar `tenant_id`/policy por hábito do padrão recente.

---

### Task 0: Baseline

- [ ] `./scripts/test-summary.sh` na worktree recém-criada (backend + frontend). Falha
      pré-existente → parar e abrir issue antes de prosseguir (não é desta feature).

---

### Task 1: Contrato — `api-spec/openapi.yaml`

**Files:**
- Modify: `api-spec/openapi.yaml`

Adiciona, no grupo de `/auth/login|register|accept-invite`:

- `POST /auth/forgot-password` — request `{email}` (required), response `200` sem corpo, sem
  `security`.
- `POST /auth/reset-password` — request `{token, newPassword}` (required), response `200` sem
  corpo, `400` sem corpo, sem `security`.

Run: `./scripts/api-sync.sh`
Expected: gera interfaces Spring (`AuthApi`) e client Orval sem erro; `auth.service.ts`
regenerado é removido pelo próprio script (gotcha conhecido).

---

### Task 2: Migration V37 — tabela + coluna de revogação

**Files:**
- Create: `backend/src/main/resources/db/migration/V37__password_reset_tokens.sql`

```sql
CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    token VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens(user_id);

ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMP NULL;
```

---

### Task 3: `PasswordResetToken` entity + repository (TDD)

**Files:**
- Create: `backend/src/main/java/com/fintech/api/domain/passwordreset/PasswordResetToken.java`
- Create: `backend/src/main/java/com/fintech/api/repository/PasswordResetTokenRepository.java`
- Modify: `backend/src/main/java/com/fintech/api/domain/user/User.java` (campo
  `passwordChangedAt`)

Entity espelha `Invitation.java` (campos: `id`, `user` (`@ManyToOne`), `token`, `expiresAt`,
`used`, `createdAt`). Repository: `findByToken(String)`.

---

### Task 4: `TokenService` — `issuedAt` no JWT + leitura

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/config/TokenService.java`
- Modify: `backend/src/test/java/com/fintech/api/config/TokenServiceTest.java`

`generateToken`: adiciona `.withIssuedAt(Instant.now())` ao builder (existente `.withExpiresAt`
já usa `Instant`/`Date` — mesma lib, `java-jwt:4.4.0` suporta `withIssuedAt(Instant)`).

Novo método:
```java
public Instant getIssuedAt(String token) {
    try {
        Algorithm algorithm = Algorithm.HMAC256(secret);
        return JWT.require(algorithm).withIssuer("fintech-api").build()
                .verify(token).getIssuedAtAsInstant();
    } catch (Exception e) {
        return null;
    }
}
```

Teste novo: gera token, `getIssuedAt` retorna instante próximo de `Instant.now()` (tolerância de
poucos segundos). Token inválido → `null`.

Run: `./mvnw test -Dtest=TokenServiceTest`
Expected: PASS.

---

### Task 5: `SecurityFilter` — rejeita token emitido antes da troca de senha

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/config/SecurityFilter.java`
- Modify: `backend/src/test/java/com/fintech/api/config/SecurityFilterTest.java`

Depois de resolver `userDetails` (e antes de setar `Authentication`): se
`user.getPasswordChangedAt() != null`, busca `tokenService.getIssuedAt(token)`; se não-nulo e
anterior a `passwordChangedAt` (convertido via `ZoneId.systemDefault()`), trata como token
inválido — mesmo log WARN e **não** seta `SecurityContextHolder` (mesmo caminho de "token
inválido" já existente, não um caminho novo).

Teste novo: usuário com `passwordChangedAt` recente + token com `issuedAt` anterior → request
não autentica (contexto de segurança vazio). Teste de regressão: usuário sem
`passwordChangedAt` (`null`, caso de todo usuário hoje) → comportamento idêntico ao atual
(nenhuma chamada a `getIssuedAt` deveria alterar o resultado — confirmar que os testes
existentes de `SecurityFilterTest` continuam verdes sem modificação).

Run: `./mvnw test -Dtest=SecurityFilterTest,AccountControllerTest,TransactionControllerTest`
Expected: PASS — controllers tests existentes (mock só `validateToken`, sem stub de
`getIssuedAt`) continuam verdes por construção (Mockito retorna `null` sem stub → check pulado).

---

### Task 6: DTOs

**Files:**
- Create: `backend/src/main/java/com/fintech/api/dto/ForgotPasswordDTO.java`
- Create: `backend/src/main/java/com/fintech/api/dto/ResetPasswordDTO.java`

```java
public record ForgotPasswordDTO(@NotBlank @Email String email) {}

public record ResetPasswordDTO(
    @NotBlank String token,
    @NotBlank @Size(max = 72)
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$",
             message = "Senha deve ter no mínimo 8 caracteres, incluindo letra maiúscula, minúscula e número")
    String newPassword
) {}
```

(Mesma regex de `AcceptInviteDTO` — política de senha única no projeto.)

---

### Task 7: `EmailService`

**Files:**
- Create: `backend/src/main/java/com/fintech/api/service/EmailService.java`
- Modify: `backend/pom.xml` (`spring-boot-starter-mail`)
- Modify: `backend/src/main/resources/application.properties` (+ `application-prod.properties`
  se envs de prod exigirem override — checar padrão existente antes de assumir)

Wrapper fino sobre `JavaMailSender.send(SimpleMailMessage)` — método
`sendPasswordResetEmail(String to, String link)`. Texto simples (sem template HTML — fora de
escopo, YAGNI até existir motivo pra investir em template).

`spring.mail.*` via env var (`MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`) — nunca
hardcoded, mesmo padrão de `JWT_SECRET`/`DATABASE_URL` (`fintech-core-config-and-flags`).

---

### Task 8: `PasswordResetService` (TDD)

**Files:**
- Create: `backend/src/main/java/com/fintech/api/service/PasswordResetService.java`
- Create: `backend/src/test/java/com/fintech/api/service/PasswordResetServiceTest.java`

```java
@Transactional
public void requestReset(ForgotPasswordDTO dto) {
    userRepository.findByEmail(dto.email())
        .filter(User::isEnabled)
        .ifPresent(user -> {
            PasswordResetToken t = new PasswordResetToken();
            t.setUser(user);
            t.setToken(UUID.randomUUID().toString());
            t.setExpiresAt(LocalDateTime.now().plusHours(1));
            tokenRepository.save(t);
            emailService.sendPasswordResetEmail(user.getEmail(), frontendUrl + "/reset-password?token=" + t.getToken());
        });
    // sem else — resposta do controller é sempre 200, aqui não há o que retornar
}

@Transactional
public void reset(ResetPasswordDTO dto) {
    PasswordResetToken t = tokenRepository.findByToken(dto.token())
        .orElseThrow(() -> new BusinessException("Token inválido"));
    if (t.isUsed() || t.getExpiresAt().isBefore(LocalDateTime.now()))
        throw new BusinessException("Token inválido");

    User user = t.getUser();
    user.setPasswordHash(passwordEncoder.encode(dto.newPassword()));
    user.setPasswordChangedAt(LocalDateTime.now());
    userRepository.save(user);

    t.setUsed(true);
    tokenRepository.save(t);
}
```

(Verificar se `BusinessException` existente aceita mensagem simples ou se precisa de outra
exception do pacote `exception/` — seguir o padrão já usado por `InviteExpiredException` etc.
antes de introduzir uma nova classe sem necessidade.)

Testes: email inexistente → `requestReset` não lança, não chama `emailService` (verify never);
email existente/ativo → chama `emailService` com link contendo o token salvo; usuário inativo →
não envia. `reset`: token válido → troca hash, seta `passwordChangedAt`, marca `used`; token
usado/expirado/inexistente → lança, hash não muda (verify `userRepository.save` never).

Run: `./mvnw test -Dtest=PasswordResetServiceTest`
Expected: PASS.

---

### Task 9: Rate limit em `forgot-password`

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/config/LoginRateLimiter.java` (generaliza:
  tira `@Value` dos campos, recebe por construtor)
- Modify: config de beans (local a decidir na execução — `@Configuration` dedicada ou
  `@Bean` direto na classe de config existente) para expor `loginRateLimiter` e
  `forgotPasswordRateLimiter` com properties próprias (`security.rate-limit.*` vs
  `security.rate-limit.forgot-password.*`)
- Modify: `backend/src/main/java/com/fintech/api/controller/AuthController.java`

Mesmo padrão do login: bloqueia por email, `429` + `Retry-After` se estourar.

**Atenção ao ajustar `LoginRateLimiter`:** ele já tem um `@Scheduled` de sweep e testes próprios
(`LoginRateLimiterTest` — confirmar nome exato antes de editar) que usam `ReflectionTestUtils`
pra injetar `window`. Generalizar o construtor não pode quebrar esses testes — rodar
isoladamente antes de seguir.

Run: `./mvnw test -Dtest=LoginRateLimiterTest,AuthControllerTest`
Expected: PASS.

---

### Task 10: `AuthController` — endpoints

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/controller/AuthController.java`
- Modify: `backend/src/test/java/com/fintech/api/controller/AuthControllerTest.java`

```java
@PostMapping("/forgot-password")
public ResponseEntity<Void> forgotPassword(@RequestBody @Valid ForgotPasswordDTO dto) {
    if (forgotPasswordRateLimiter.isBlocked(dto.email())) {
        long retryAfter = forgotPasswordRateLimiter.secondsUntilUnblock(dto.email());
        return ResponseEntity.status(429).header("Retry-After", String.valueOf(retryAfter)).build();
    }
    forgotPasswordRateLimiter.registerFailure(dto.email()); // conta toda tentativa, sucesso ou não — não há "sucesso" observável aqui
    passwordResetService.requestReset(dto);
    return ResponseEntity.ok().build();
}

@PostMapping("/reset-password")
public ResponseEntity<Void> resetPassword(@RequestBody @Valid ResetPasswordDTO dto) {
    passwordResetService.reset(dto); // BusinessException → GlobalExceptionHandler mapeia pra 400
    return ResponseEntity.ok().build();
}
```

Testes MockMvc: `forgot-password` sempre 200 (email existente e inexistente), 429 após N
tentativas; `reset-password` 200 com token válido, 400 com token inválido/expirado.

Run: `./mvnw test -Dtest=AuthControllerTest`
Expected: PASS.

---

### Task 11: `SecurityConfigurations` — rotas públicas

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/config/SecurityConfigurations.java`

```java
.requestMatchers(HttpMethod.POST, "/auth/forgot-password").permitAll()
.requestMatchers(HttpMethod.POST, "/auth/reset-password").permitAll()
```

Run: `./mvnw test -Dtest=SecurityFilterTest` (smoke — confirmar que a config carrega).

---

### Task 12: Frontend — telas

**Files:**
- Create: `frontend/src/app/features/auth/forgot-password/{forgot-password.ts,html,scss,spec.ts}`
- Create: `frontend/src/app/features/auth/reset-password/{reset-password.ts,html,scss,spec.ts}`
- Modify: `frontend/src/app/core/services/auth.ts` (métodos `forgotPassword`, `resetPassword`
  sobre o client Orval gerado na Task 1)
- Modify: `frontend/src/app/app.routes.ts` (2 rotas públicas, mesmo bloco de
  `login`/`register`/`accept-invite`)
- Modify: `frontend/src/app/features/auth/login/login.html` (link "Esqueci minha senha")

`forgot-password`: form com email, submit → mensagem genérica de sucesso sempre (nunca
diferencia email existente/inexistente no frontend também — a UI não pode reintroduzir a
enumeração que o backend evitou).

`reset-password`: lê `token` de `ActivatedRoute.queryParamMap` (mesmo padrão de
`accept-invite.ts` — verificar antes de escrever), form com nova senha + confirmação (validação
client-side de campos iguais), submit → sucesso navega pra `/login` com mensagem.

Run: `npm test` (não `npx vitest` cru — gotcha do projeto)
Expected: specs novos PASS, nenhuma regressão nos existentes.

---

### Task 13: Dataset e documentação

**Files:**
- Modify: `docs/http/seed-dataset.http` (requests `forgot-password`/`reset-password`)
- Modify: `database-schema.md` (linha V37)
- Modify: `summary.md` (seção Auth & Convites — 2 linhas novas na tabela de endpoints + nota do
  comportamento anti-enumeração e da revogação de sessão)
- Modify: `domain.md` (entidade `PasswordResetToken` no diagrama; campo `passwordChangedAt` em
  `User`)

**Seed SQL:** nenhuma linha em `V13`/seed — token real commitado no repo é risco de segurança
(mesmo que expire em 1h, é hábito ruim), e a tabela não precisa de dado representativo pra
nenhum teste de fixture existente (decisão já registrada na spec, seção "Fora de escopo" por
omissão — dataset.md permite "sem atualização" quando não há necessidade real de dado
representativo; aqui a necessidade é negativa por design de segurança).

---

### Task 14: Regressão completa

- [ ] `./mvnw test -Dspring.profiles.active=local` (background, >7min) ou
      `./scripts/test-summary.sh backend`.
- [ ] `./scripts/test-summary.sh frontend`.

---

## Fim — critério de conclusão

- [ ] Contrato (`openapi.yaml`) + codegen sincronizados.
- [ ] `V37` aplicada, `password_reset_tokens` + `users.password_changed_at`.
- [ ] Fluxo completo funcional ponta a ponta (testado manualmente via `docs/http/seed-dataset.http`
      ou Swagger UI, com SMTP real ou um provedor de teste tipo Mailtrap — decidir na execução
      se vale configurar um sandbox de SMTP pro dev local).
- [ ] Sessão antiga (JWT emitido antes do reset) para de autenticar após a troca — validado por
      teste automatizado (Task 5) e, se possível, manualmente.
- [ ] Rate limit ativo em `forgot-password`.
- [ ] Frontend: telas + link, specs verdes.
- [ ] Regressão completa (back + front) sem quebra.
- [ ] `database-schema.md`/`summary.md`/`domain.md` atualizados.
- [ ] SemVer sugerido: MINOR.
