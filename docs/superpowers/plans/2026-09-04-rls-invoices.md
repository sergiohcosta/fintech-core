# RLS — invoices (rollout, ciclo 3) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** estender RLS pra `invoices` (posição 3 do rollout, #116, ADR-006, spec
`2026-09-02-rls-rollout-sistema-todo-design.md`).

**Achado do levantamento (antes de codar):** único writer é `InvoiceService`
(`createNewInvoice`, `close`, `pay`), mas **dois métodos não têm `User` no argumento** —
`close(UUID id, Tenant tenant)` (só `Tenant`) e `getOrCreate`/`createNewInvoice(Account
account, ...)` (só `Account`; `createNewInvoice` roda em
`@Transactional(propagation = REQUIRES_NEW)`, transação física separada — o `SET LOCAL` da
chamada externa não propaga pra ela, cada uma precisa resolver o tenant por conta própria).
O fallback atual do `TenantRlsAspect` só olha `User` nos argumentos — não pega nenhum dos
dois. Em tráfego HTTP real isso não quebra (`SecurityContextHolder` resolve primeiro), mas
testes que chamam o service direto (sem HTTP) quebrariam.

**Arquitetura:** generaliza o fallback do aspect pra também aceitar `Tenant` e `Account` nos
argumentos (extrai `.getTenant()` de `Account`), antes das migrations — é fundação, não
específico de `invoices`, beneficia qualquer método futuro nesse padrão.

**Tech Stack:** Java 21, Spring AOP, Flyway, `@SpringBootTest`.

## Global Constraints

- Próxima migration livre: **V36**.
- `InvoiceServicePaymentConcurrencyTest.createClosedInvoiceWithPendingTransaction` grava
  `invoices` direto pelo repositório, FORA do `SET LOCAL` já existente (que hoje só envolve o
  `transactionRepository.save`) — precisa mover pra dentro do mesmo bloco.
  `DashboardAggregatesRepositoryTest` já tem `SET LOCAL` único por teste (`@BeforeEach`); a
  gravação de `Invoice` em `invoicePaymentExcludedFromAggregates` já deveria estar coberta —
  confirmar na Task 3, não assumir.
- SemVer: nenhum impacto.

---

### Task 1: Generaliza o fallback do `TenantRlsAspect`

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/config/TenantRlsAspect.java`

Fallback passa a checar, nesta ordem, o primeiro argumento que resolva tenant: `User`
(existente) → `Tenant` (novo, direto) → `Account` (novo, via `.getTenant()`).

Run: `./mvnw test -Dtest=TenantRlsAspectTest,StagedTransactionsRlsTest,ImportBatchesRlsTest`
Expected: PASS (generalização não muda comportamento dos casos já cobertos).

---

### Task 2: Migration V36

**Files:**
- Create: `backend/src/main/resources/db/migration/V36__invoices_rls.sql`

```sql
ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoices FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON invoices
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
```

---

### Task 3: Teste discriminante + ajuste de fixtures

**Files:**
- Create: `backend/src/test/java/com/fintech/api/config/InvoicesRlsTest.java`
- Modify: `backend/src/test/java/com/fintech/api/service/InvoiceServicePaymentConcurrencyTest.java`

Discriminante: mesmo molde dos ciclos anteriores (fail-safe deny, isolamento, fluxo real via
`InvoiceService.getOrCreate` — cobre especificamente o caso `REQUIRES_NEW` da Task 1).

`InvoiceServicePaymentConcurrencyTest`: move `invoiceRepository.save(...)` de
`createClosedInvoiceWithPendingTransaction` pra dentro do bloco `TransactionTemplate` já
existente (mesma transação/tenant que o `transactionRepository.save`).

Confirmar (sem assumir) se `DashboardAggregatesRepositoryTest` precisa de ajuste — rodar
isolado antes de seguir.

Run: `./mvnw test -Dtest=InvoicesRlsTest,InvoiceServicePaymentConcurrencyTest,DashboardAggregatesRepositoryTest,InvoiceControllerTest,InvoiceServiceTest`

---

### Task 4: Regressão completa

- [ ] `./mvnw test -Dspring.profiles.active=local` (background, >7min).

---

### Task 5: Documentação

- [ ] `database-schema.md`: linha V36.

---

## Fim deste ciclo — critério

- [ ] Fallback do aspect generalizado (`User`/`Tenant`/`Account`).
- [ ] `invoices` com `ENABLE`+`FORCE`+policy, teste discriminante verde.
- [ ] Regressão completa sem quebra.
- [ ] Próximo ciclo (fora deste plano): `installment_groups` (posição 4).
