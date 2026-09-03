# RLS — staged_transactions + import_batches (rollout, ciclo 2) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** estender RLS (defesa em profundidade, #116, ADR-006) pra `staged_transactions` e
`import_batches` — posições 1 e 2 da ordem de rollout definida em
`docs/superpowers/specs/2026-09-02-rls-rollout-sistema-todo-design.md`. As duas juntas neste
ciclo porque compartilham um único writer (`ImportService`, todos os métodos com `User` no
parâmetro) — mesmo racional, mesmo risco, investigação idêntica.

**Arquitetura:** mesmo padrão do PoC (V33) e já coberto pelo `TenantRlsAspect` genérico
(fase 1) — nenhuma mudança de aspect necessária aqui, só migrations + testes. Único ajuste de
teste: `ImportServiceTest.cleanupTenant()` precisa envolver os 3 deletes relacionados
(`staged_transactions`, `import_batches`, `transactions`) no mesmo `SET LOCAL`, ordem segura
de FK (staged → batches → transactions).

**Tech Stack:** Java 21, Flyway, `@SpringBootTest` contra Postgres local (`fintech_app`).

## Global Constraints

- Próximas migrations livres: **V34** (`staged_transactions`), **V35** (`import_batches`).
- `staged_transactions.tenant_id` é **denormalizado** por design (V23) — não é FK direta pro
  fluxo de leitura, é a defesa nº1 documentada contra vazamento; RLS aqui é uma SEGUNDA
  camada sobre uma decisão de design que já existia pro mesmo objetivo.
- Nenhuma exceção tipo `TenantRegistrationService` — confirmado por leitura: todos os métodos
  de `ImportService` recebem `User`.
- SemVer: nenhum impacto.
- `database-schema.md`: duas linhas novas (V34, V35).

---

### Task 1: Migrations

**Files:**
- Create: `backend/src/main/resources/db/migration/V34__staged_transactions_rls.sql`
- Create: `backend/src/main/resources/db/migration/V35__import_batches_rls.sql`

```sql
-- V34
ALTER TABLE staged_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE staged_transactions FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON staged_transactions
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
```
```sql
-- V35
ALTER TABLE import_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE import_batches FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON import_batches
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
```

**Não rodar isolado** — igual ao V33, quebra tudo que grava nessas tabelas até o teste do
`ImportServiceTest` (que já cobre o aspect, transparente) confirmar que segue funcionando.

---

### Task 2: Teste discriminante por tabela

**Files:**
- Create: `backend/src/test/java/com/fintech/api/config/StagedTransactionsRlsTest.java`
- Create: `backend/src/test/java/com/fintech/api/config/ImportBatchesRlsTest.java`

Mesmo molde de `TenantRlsAspectTest` (PoC): 2 tenants, fixtures via `SET LOCAL` manual no
`@BeforeEach` + `entityManager.flush()` (mesmo gotcha de flush adiado do PoC), 3 casos:
1. Sem `app.tenant_id` → 0 linhas.
2. Com tenant A → nunca vaza tenant B, query nativa sem `WHERE`.
3. Fluxo real via `ImportService` (`createBatch`/`listStaged`) funciona transparente.

---

### Task 3: Ajusta `ImportServiceTest.cleanupTenant()`

**Files:**
- Modify: `backend/src/test/java/com/fintech/api/service/imports/ImportServiceTest.java`

De:
```java
jdbc.update("DELETE FROM staged_transactions WHERE tenant_id = ?", tenantId);
jdbc.update("DELETE FROM import_batches WHERE tenant_id = ?", tenantId);
new TransactionTemplate(txManager).executeWithoutResult(status -> {
    jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
    jdbc.update("DELETE FROM transactions WHERE tenant_id = ?", tenantId);
});
jdbc.update("DELETE FROM installment_groups WHERE tenant_id = ?", tenantId);
```
Para (unifica os 3 relacionados num só bloco, ordem de FK):
```java
new TransactionTemplate(txManager).executeWithoutResult(status -> {
    jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
    jdbc.update("DELETE FROM staged_transactions WHERE tenant_id = ?", tenantId);
    jdbc.update("DELETE FROM import_batches WHERE tenant_id = ?", tenantId);
    jdbc.update("DELETE FROM transactions WHERE tenant_id = ?", tenantId);
});
jdbc.update("DELETE FROM installment_groups WHERE tenant_id = ?", tenantId);
```

Run: `./mvnw test -Dtest=ImportServiceTest,StagedTransactionsRlsTest,ImportBatchesRlsTest`
Expected: PASS.

---

### Task 4: Regressão completa

- [ ] `./mvnw test -Dspring.profiles.active=local` (background, >7min).
Expected: 426 + testes novos, 0 falhas.

---

### Task 5: Documentação

- [ ] `database-schema.md`: linhas V34/V35.

---

## Fim deste ciclo — critério

- [ ] `staged_transactions` e `import_batches` com `ENABLE`+`FORCE`+policy.
- [ ] Testes discriminantes das duas, verdes.
- [ ] `cleanupTenant()` unificado, sem regressão.
- [ ] Próximo ciclo (fora deste plano): `invoices` (posição 3).
