# Parcelas somente em faturas abertas — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or
> `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`)
> syntax for tracking.
>
> **Spec:** `docs/superpowers/specs/2026-10-09-parcelas-somente-faturas-abertas-design.md`

**Goal:** Compra parcelada manual e materialização de recorrência passam a criar transações
**somente em faturas abertas**; parcelas que cairiam em fatura `CLOSED`/`PAID` são descartadas
(não criadas, sem renumerar). Importação permanece com a âncora explícita. Frontend confirma via
um preview server-side.

**Architecture:** Backend Spring (Controller → Service → Repository). Ponto único de decisão no
`TransactionService` com flag `skipClosedInvoices`; leitura de fatura sem materialização via
`InvoiceService.findExisting`; endpoint de preview read-only. Frontend Angular Zoneless/Signals
consumindo o client Orval.

**Tech Stack:** Java 21 + Spring Boot + JPA + Flyway; JUnit 5 + Mockito + AssertJ + MockMvc;
Angular (Zoneless, Signals, Angular Material 3, SCSS); Orval (client gerado de `api-spec/openapi.yaml`).

## Global Constraints

- Toda mudança de schema seria via migration Flyway — **esta feature não muda schema** (nenhuma
  migration).
- Nenhuma query de negócio sem escopo de tenant: a conta já vem de `resolveAccount(dto.accountId(), user)`.
- Nunca expor entidade JPA no controller — sempre DTO.
- Proibido `any` no TypeScript; Zoneless (não usar APIs dependentes de `zone.js`); Signals primeiro.
- Contrato nasce em `api-spec/openapi.yaml` **antes** da implementação; depois rodar
  `./scripts/api-sync.sh` (não executar os passos manualmente).
- Commits PT-BR imperativos, sem `Co-Authored-By`. Spec e plano commitados antes da worktree.
- Não editar migration/seed já aplicados; não editar nada em `develop`/`main` dentro de `backend/src`,
  `frontend/src`, `api-spec/`.
- Suíte backend demora >7 min: usar `./scripts/test-summary.sh backend` ou `-Dtest=Classe` para
  feedback rápido; rodar em background quando for a suíte inteira. Frontend via `npm test` /
  `./scripts/test-summary.sh frontend` (nunca `npx vitest` cru).

---

## Pré-requisitos (fora da worktree)

- [ ] **P0. Baseline verde + worktree.** Na raiz estável (`/home/sergio/projetos/fintech-core`):
  - [ ] `./scripts/test-summary.sh backend` e `./scripts/test-summary.sh frontend` — se houver
        falha pré-existente, abrir issue imediatamente (não tolerar baseline vermelho).
  - [ ] Spec e plano commitados em `develop` (`docs(spec): ...` / `docs(plan): ...`).
  - [ ] `git worktree add -b feat/parcelas-faturas-abertas ~/fintech-core/.worktrees/parcelas-faturas-abertas develop`
  - [ ] A partir daqui, todo `git` com path absoluto ou `git -C <worktree>`; nunca prefixar path já relativo.

---

## Task 1 — `InvoiceService`: leitura sem materialização + helper de datas

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/InvoiceService.java`
- Test: `backend/src/test/java/com/fintech/api/service/InvoiceServiceTest.java`

- [ ] Adicionar `findExisting(Account, int, int)` → `Optional<Invoice>` (`@Transactional(readOnly = true)`),
      delegando a `repository.findByAccountAndReferenceYearAndReferenceMonth`.
- [ ] Extrair o cálculo de `(closingDate, dueDate)` de `createNewInvoice` para um helper
      reutilizável (ex.: `static InvoiceSchedule scheduleFor(int year, int month, int closingDay, int dueDay)`,
      com um `record InvoiceSchedule(LocalDate closingDate, LocalDate dueDate)`), mantendo o
      `atDayCapped` e a regra `dueDay >= closingDay`. `createNewInvoice` passa a usá-lo (sem mudar comportamento).
- [ ] Testes: `findExisting` retorna vazio quando não existe (e **não** cria nada); `scheduleFor`
      cobre `dueDay >= closingDay` e `dueDay < closingDay`, além do cap de dia (ex.: closingDay=31 em fev).
- [ ] Rodar: `./mvnw -f backend/pom.xml test -Dtest=InvoiceServiceTest`.

## Task 2 — `TransactionService.create`: descarte de parcelas em fatura fechada

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/TransactionService.java`
- Test: `backend/src/test/java/com/fintech/api/service/TransactionServiceTest.java`

- [ ] Introduzir o overload privado `create(dto, user, anchorInvoiceMonth, boolean skipClosedInvoices)`.
      A sobrecarga pública de 2 args chama `(dto, user, null, true)`; a de 3 args (importação/testes)
      chama `(dto, user, anchor, false)`.
- [ ] No loop: quando `skipClosedInvoices` e `isCreditCard`, consultar `invoiceService.findExisting(...)`;
      se existir com status `!= OPEN`, `continue` (não cria transação nem fatura). Caso contrário,
      `getOrCreate` como hoje.
- [ ] Criar o `InstallmentGroup` **lazy**, na primeira parcela efetivamente criada (D4: se todas
      forem descartadas, nenhum grupo é criado).
- [ ] Manter `installmentNumber = i + 1` e `totalInstallments = N` mesmo com descartes; manter a
      divisão de centavos (`DOWN` + última parcela absorve o resíduo).
- [ ] Testes (ver seção Teste da spec): descarte por `PAID` e por `CLOSED`; numeração `3/6..6/6`;
      todos descartados → lista vazia e sem grupo; sem descarte → comportamento atual; conta não-cartão
      inalterada; sobrecarga de 3 args **não** descarta (regressão da importação).
- [ ] Ajustar o teste do invariante "soma das parcelas == total" (~linha 103) para deixar explícito
      que ele vale apenas sem descarte.
- [ ] Rodar: `./mvnw -f backend/pom.xml test -Dtest=TransactionServiceTest`.

## Task 3 — `materializeFromRule`: recusa em fatura fechada (409)

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/TransactionService.java`
- Modify: `backend/src/test/java/com/fintech/api/service/TransactionServiceTest.java` (ou `RecurrenceRuleServiceTest.java`)

- [ ] Quando a conta for `CREDIT_CARD` e `invoiceService.findExisting(...)` retornar fatura com
      status `!= OPEN`, lançar `BusinessConflictException` (→ 409 no `GlobalExceptionHandler`)
      com mensagem clara (mês/ano + status).
- [ ] Teste: ocorrência cuja fatura está `CLOSED` e `PAID` → `BusinessConflictException`, sem
      transação persistida; ocorrência em fatura `OPEN`/inexistente → materializa normalmente.
- [ ] Rodar: `./mvnw -f backend/pom.xml test -Dtest=TransactionServiceTest,RecurrenceRuleServiceTest`.

## Task 4 — Preview server-side (`POST /api/transactions/installment-preview`)

**Files:**
- Create: `backend/src/main/java/com/fintech/api/dto/transaction/InstallmentPreviewRequestDTO.java`
- Create: `backend/src/main/java/com/fintech/api/dto/transaction/InstallmentPreviewDTO.java`
- Modify: `backend/src/main/java/com/fintech/api/service/TransactionService.java` (`previewInstallments` read-only)
- Modify: `backend/src/main/java/com/fintech/api/controller/TransactionController.java`
- Test: `backend/src/test/java/com/fintech/api/service/TransactionServiceTest.java` e `.../controller/TransactionControllerTest.java`

- [ ] `InstallmentPreviewRequestDTO` com Bean Validation (`amount`, `date`, `accountId`; `totalInstallments` opcional).
- [ ] `InstallmentPreviewDTO` com `installmentNumber`, `totalInstallments`, `amount`,
      `referenceYear`, `referenceMonth`, `closingDate`, `dueDate`, `invoiceId`, `invoiceStatus`,
      `willCreate`.
- [ ] `previewInstallments(dto, user)`: valida conta `CREDIT_CARD` (senão `BusinessException` → 400);
      para cada `i` usa `resolveInvoiceMonth` + `scheduleFor` + `findExisting`; `willCreate = true`
      quando a fatura não existe ou está `OPEN`. **Sem nenhum efeito colateral** (não chama `getOrCreate`).
- [ ] Endpoint `POST /installment-preview` no `TransactionController` (autenticado; sem regra de role).
- [ ] Testes: preview com 1ª parcela em `PAID` → `willCreate=false` nas afetadas; conta não-cartão → 400;
      controller 200 com lista e shape esperado.
- [ ] Rodar: `./mvnw -f backend/pom.xml test -Dtest=TransactionServiceTest,TransactionControllerTest`.

## Task 5 — Contrato: `openapi.yaml` + `api-sync.sh`

**Files:**
- Modify: `api-spec/openapi.yaml`
- Modify (gerados): `frontend/src/app/core/api/**`, `backend/.../generated/**` (via script)

- [ ] Adicionar `POST /api/transactions/installment-preview` com request/response e as duas novas
      schemas.
- [ ] Adicionar `skippedInstallments` (integer) em `InstallmentGroupResponseDTO`.
- [ ] Rodar `./scripts/api-sync.sh` (faz cópia + generate-sources + orval + remove `auth.service.ts`
      regenerado). Conferir que o `auth.service.ts` não ficou sujo/regenerado.
- [ ] Implementar o campo `skippedInstallments` em `InstallmentGroupService.toDTO`
      (`totalInstallments − txs.size()`) e ajustar o teste de contagem do grupo.

## Task 6 — Frontend: diálogo de confirmação + integração (lane `@designer`)

**Files:**
- Create: componente de diálogo (ex.: `frontend/src/app/features/transaction/installment-confirm-dialog/`)
- Modify: `frontend/src/app/features/transaction/transaction-form/transaction-form.ts` (+ `.html`)
- Test: specs Vitest das partes puras

- [ ] Diálogo standalone (MatDialog + Signals/Zoneless) listando as parcelas: número, valor,
      fatura (mês/ano), vencimento e estado ("criada" / "ignorada — fatura fechada/paga").
      Visual/UX é do `@designer`; o texto final passa por revisão do orquestrador.
- [ ] `transaction-form`: no submit de compra parcelada no cartão, chamar `installmentPreview`
      primeiro; se houver `willCreate == false`, abrir o diálogo e só criar após confirmação;
      sem descartes, criar direto.
- [ ] Exibir erro 409 da recorrência como mensagem no fluxo de confirmação de ocorrência.
- [ ] Testes com `./scripts/test-summary.sh frontend` (ou `npm test`); não usar `npx vitest` cru.

## Task 7 — Documentos de estado + dataset

- [ ] `summary.md`: regra "parcelamento manual/recorrência só cria em fatura aberta; descarte de
      parcela em fatura fechada/paga; endpoint de preview; `skippedInstallments`".
- [ ] `domain.md`: §3 (faturas) e §5 (parcelamento) com a regra de descarte e a consequência no
      invariante soma.
- [ ] `docs/http/seed-dataset.http`: request do novo endpoint de preview.
- [ ] `database-schema.md`: confirmar que não há mudança (nada a fazer) — registrar em nota, se aplicável.

## Task 8 — Fechamento

- [ ] `./scripts/test-summary.sh backend` e `./scripts/test-summary.sh frontend` verdes na worktree.
- [ ] Revisão de tenant isolation e ausência de `any`/`console.log`/`Co-Authored-By`.
- [ ] Aprovação explícita do dev → merge em `develop` → `./scripts/clean-worktrees.sh`.
- [ ] PR cumulativa `develop → main` com o template preenchido e **Impacto SemVer: MINOR**.

---

## Notas de risco

- **Invariante `soma(parcelas) == total` deixa de valer quando há descarte** — é intencional
  (as parcelas descartadas representam o que já foi pago fora do sistema). O teste e a doc de
  domínio devem refletir isso.
- **Importação sem fatura-alvo (`null`)**: a sobrecarga de 3 args usa `skipClosedInvoices=false`,
  então continua inalterada — validar com teste de regressão (`ImportServiceTest`).
- **Corrida preview→submit**: a fatura pode ser paga entre o preview e o `create`. O backend é a
  autoridade; o pior caso é a parcela ser descartada mesmo com o preview dizendo "criada" (ou
  vice-versa em teoria), nunca gravar em fatura fechada.
