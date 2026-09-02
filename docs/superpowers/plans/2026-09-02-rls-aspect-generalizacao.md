# RLS — generalização do TenantRlsAspect (Fase 1 do rollout) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** trocar o pointcut enumerado do `TenantRlsAspect` (hoje só `TransactionService.*` +
`InvoiceService.pay`) por um pointcut genérico sobre `@Transactional` em todo
`com.fintech.api.service`, com resolução de tenant `SecurityContextHolder`-primeiro e
parâmetro `User` como fallback — fundação necessária antes de rolar RLS pra qualquer tabela
nova (issue #116, ADR-006, spec `2026-09-02-rls-rollout-sistema-todo-design.md`). Sem código
novo em `transactions` nesta fase — só generaliza o mecanismo já provado no PoC.

**Arquitetura:** ver a spec, seções "Correção de rota" e "Resolução de tenant". Duas
exceções conhecidas (`TenantRegistrationService.register`, `InvitationService.accept`)
recebem `SET LOCAL` manual inline, porque o tenant nasce/é resolvido DENTRO do método, não
chega pronto via autenticação nem argumento.

**Tech Stack:** Java 21, Spring AOP, JUnit 5 + `@SpringBootTest` contra Postgres local.

## Global Constraints

- `transactions` continua a única tabela com RLS ativo nesta fase — as outras 12 entram em
  planos separados, um por tabela (ou pequeno lote), depois desta fundação estar mergeada.
- Baseline verde obrigatória antes de começar (426/426, estado atual da `develop` após o PoC).
- Nenhum teste hoje verde pode ficar vermelho — o pointcut genérico intercepta MAIS métodos
  que antes; qualquer regressão aqui é sinal de outro write path esquecido (mesmo padrão dos
  achados do PoC) e deve ser tratado, não contornado.
- SemVer: nenhum impacto — mudança interna, `openapi.yaml` intocado.
- `database-schema.md`/`architecture.md`: sem mudança de schema nesta fase (só código), nada
  a atualizar além de uma nota se fizer sentido.

---

### Task 1: Levantamento — confirma a lista de exceções antes de codar

**Files:** nenhum arquivo alterado — só leitura.

- [ ] **Step 1: Audita endpoints públicos que gravam em tabela com RLS (hoje só `transactions`,
  mas o padrão vale pra quando as outras entrarem)**

Run:
```bash
grep -n "Público" summary.md   # confirma a lista: /auth/{login,register,accept-invite}, GET /invites/{token}
grep -rn "@Transactional" backend/src/main/java/com/fintech/api/service/TenantRegistrationService.java \
  backend/src/main/java/com/fintech/api/service/InvitationService.java \
  backend/src/main/java/com/fintech/api/service/TokenService.java
```
Expected: confirmar que `register`/`accept` são os dois métodos que gravam dado sem
`User`/SecurityContext disponível (já confirmado por leitura na spec) — `login` não grava
nada em tabela de negócio (só lê `User`, gera JWT), `GET /invites/{token}` é `readOnly`. Se o
grep revelar um TERCEIRO método no mesmo padrão, ele entra nesta fase também.

- [ ] **Step 2: Baseline verde**

Run: `./mvnw test -Dspring.profiles.active=local` em background (>7min).
Expected: 426/426 (estado atual pós-PoC).

---

### Task 2: Generaliza o pointcut do `TenantRlsAspect`

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/config/TenantRlsAspect.java`

- [ ] **Step 1: Troca o pointcut**

De:
```java
@Around("execution(public * com.fintech.api.service.TransactionService.*(..))"
        + " || execution(public com.fintech.api.dto.invoice.InvoiceResponseDTO com.fintech.api.service.InvoiceService.pay(..))")
```
Para:
```java
@Around("within(com.fintech.api.service..*) && @annotation(org.springframework.transaction.annotation.Transactional)")
```

- [ ] **Step 2: Resolução de tenant — `SecurityContextHolder` primeiro, `User` como fallback**

```java
private Optional<UUID> resolveTenantId(ProceedingJoinPoint joinPoint) {
    UUID fromAuth = SecurityUtils.currentUserOrNull() // nova variante tolerante, ver Step 3
            .map(u -> u.getTenant().getId())
            .orElse(null);
    if (fromAuth != null) return Optional.of(fromAuth);

    return Arrays.stream(joinPoint.getArgs())
            .filter(User.class::isInstance)
            .map(User.class::cast)
            .map(u -> u.getTenant().getId())
            .findFirst();
}
```
Se `resolveTenantId` voltar vazio: **não** roda `SET LOCAL`, **não** lança exceção — segue
pro `joinPoint.proceed()` normalmente (métodos que não tocam tabela com RLS não precisam;
métodos que precisam e não resolveram tenant vão falhar de forma alta e visível na policy,
nunca em silêncio).

- [ ] **Step 3: `SecurityUtils.currentUserOrNull()` — variante tolerante**

`SecurityUtils.currentUser()` hoje lança `AccessDeniedException` se não autenticado (correto
pro uso em controllers). Adicionar `currentUserOrNull()` (retorna `Optional<User>`, nunca
lança) — usado só pelo aspect, que precisa de "não autenticado" como caso normal (endpoints
públicos), não como erro.

Run: `./mvnw test -Dtest=TenantRlsAspectTest,ImportServiceTest,InvoiceServicePaymentConcurrencyTest`
Expected: PASS — os testes que hoje dependem do fallback por `User` continuam funcionando
(nenhum deles roda com `SecurityContextHolder` autenticado, então caem no fallback como
antes).

---

### Task 3: Trata as duas exceções conhecidas

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/TenantRegistrationService.java`
- Modify: `backend/src/main/java/com/fintech/api/service/InvitationService.java`

- [ ] **Step 1: Teste primeiro (RED) — prova que sem o fix, `register`/`accept` não setam
  `app.tenant_id`**

Adicionar em `TenantRegistrationServiceTest`/`InvitationServiceTest` (ou um teste de
integração novo, se os existentes forem só Mockito) uma asserção via query nativa que
confirma `current_setting('app.tenant_id', true)` bate com o tenant recém-criado, dentro da
mesma transação, logo após `register()`/`accept()` retornar. **Nota:** isso só é um RED
genuíno se `transactions`/`users` tiver RLS ativo no momento do teste — hoje só
`transactions` tem. Adaptar a asserção pra rodar contra `transactions` (ex.: criar uma
transação de teste dentro do mesmo tenant logo após, ver se falha) até `users` entrar no
rollout (plano futuro) — ou aceitar que o teste desta task fica "best-effort" até lá e
documentar isso no commit.

- [ ] **Step 2: `TenantRegistrationService.register` — `SET LOCAL` manual**

Logo após `tenant = tenantRepository.save(tenant);`, antes de `userRepository.save(adminUser)`:
```java
entityManager.unwrap(Session.class).doWork(connection -> {
    try (var statement = connection.createStatement()) {
        statement.execute("SET LOCAL app.tenant_id = '" + tenant.getId() + "'");
    }
});
```
(Injetar `EntityManager` no service — hoje não é dependência dele.)

- [ ] **Step 3: `InvitationService.accept` — `SET LOCAL` manual**

Mesma técnica, logo após `Invitation invitation = findValidInvitation(dto.token());`, antes
de `userRepository.save(user)`.

Run: `./mvnw test -Dtest=TenantRegistrationServiceTest,InvitationServiceTest`
Expected: PASS.

---

### Task 4: Regressão completa

- [ ] **Step 1:** `./mvnw test -Dspring.profiles.active=local` (background, >7min).
Expected: 426/426, igual à baseline da Task 1 — pointcut mais amplo não pode quebrar nada
que já passava.

---

## Fim desta fase

- [ ] Pointcut genérico, suíte verde.
- [ ] Duas exceções tratadas e testadas.
- [ ] Próximo passo (fora deste plano): retomar a spec de rollout, tabela por tabela, a
      partir de `staged_transactions` (posição 1 da ordem definida).
