# Fix #244 — corrida entre checagem de status e fechamento/pagamento de fatura — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or
> `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`)
> syntax for tracking.
>
> **Issue:** #244 (`chore(backend): bump tomcat-embed-core 11.0.24 → 11.0.25 (Trivy residual, #246)`)
> **Spec original:** `docs/superpowers/specs/2026-10-09-parcelas-somente-faturas-abertas-design.md`
> (limitação conhecida registrada nas linhas 216-219, a ser removida por este plano).

**Goal:** Garantir que uma parcela de cartão de crédito nunca seja anexada a uma fatura
`CLOSED`/`PAID`, mesmo sob concorrência. A correção é via lock pessimista (`SELECT ... FOR UPDATE`)
adquirido na fatura durante `create`/`materializeFromRule` e também em `close`/`pay`, com
revalidação do status sob o lock. O descarte (manual) e o 409 (recorrência) já existem
([feature `parcelas-somente-faturas-abertas`, merged em 77f7b0a](docs/superpowers/plans/2026-10-09-parcelas-somente-faturas-abertas.md));
este plano só fecha a janela de corrida entre `findExisting` e `getOrCreate`.

**Architecture:** Backend Spring (Controller → Service → Repository). Lock pessimista no
`InvoiceService` — centraliza o lock para que `TransactionService` não precise saber. O lock
participa da MESMA transação dos métodos que mutam a fatura (`close`/`pay`) e que consultam o
status antes de criar transação (`getOrCreate`/`create`/`materializeFromRule`).

**Tech Stack:** Java 21 + Spring Boot 4.0.8 + JPA/Hibernate; JUnit 5 + AssertJ + Spring Test
(Testcontainers Postgres); padrão de teste de concorrência do `InvoiceServicePaymentConcurrencyTest`
(`@SpringBootTest` sem `@Transactional`, `CyclicBarrier`, 30 iterações, assert na pós-condição).

## Global Constraints

- **Esta task NÃO muda schema** — nenhuma migration Flyway.
- **Esta task NÃO muda contrato** (`api-spec/openapi.yaml` intacto) — SemVer PATCH.
- Lock pessimista exige transação ativa: o método de entrada (`close`/`pay`/`getOrCreateForUpdate`)
  já é `@Transactional`. O caller que abre a transação (ex.: `TransactionService.create`) precisa
  estar em `@Transactional` também. JÁ ESTÁ (linhas 137, 156, 326 do `TransactionService`).
- Self-injection (`@Lazy @Autowired InvoiceService self`) só é necessária quando a propagação
  precisa ser diferente (`REQUIRES_NEW` do `getOrCreate → createNewInvoice` atual). Para o lock,
  a propagação é REQUIRED (mesma transação) — **não precisa de self-injection nova**.
- Lock de linha no Postgres: `SELECT ... FOR UPDATE` via Spring Data JPA
  (`@Lock(LockModeType.PESSIMISTIC_WRITE)` em derived query).
- Lock é por fatura: transações que mexem em faturas diferentes NÃO se bloqueiam.
- Suíte backend demora >7 min: usar `./scripts/test-summary.sh backend` ou `-Dtest=Classe` para
  feedback rápido; rodar em background quando for a suíte inteira.
- Commits PT-BR imperativos, sem `Co-Authored-By`. Plan commitado dentro da worktree.

---

## Pré-requisitos (já cumpridos)

- [x] **Spec e plano existentes.** Spec em `docs/superpowers/specs/2026-10-09-parcelas-somente-faturas-abertas-design.md`
      (merged em `77f7b0a`); este plano é a continuação.
- [x] **Baseline verde** na develop atual — suíte backend 543 testes, 0 falhas (verificado
      em `c3545cf` via v0.6.1, reaplicado após merge do a11y baseline).
- [x] **Worktree criada de develop atualizada** —
      `.worktrees/fix-244-race-condition-parcela-fatura` na branch `fix/244-race-condition-parcela-fatura`.

---

## Task 1 — `InvoiceRepository`: finder com lock pessimista

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/repository/InvoiceRepository.java`

- [ ] Adicionar método `findByAccountAndReferenceYearAndReferenceMonthForUpdate(Account, int, int)`
      anotado com `@Lock(LockModeType.PESSIMISTIC_WRITE)` (importar `org.springframework.data.jpa.repository.Lock`
      e `jakarta.persistence.LockModeType`). Javadoc explica: o lock é por linha no Postgres
      (`SELECT ... FOR UPDATE`); duas transações que disputam a MESMA fatura serializam, faturas
      diferentes não se bloqueiam.
- [ ] Rodar: `./mvnw -f backend/pom.xml compile` (sanidade de tipos).

## Task 2 — `InvoiceService`: `getOrCreate` e `close`/`pay` adquirem lock

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/InvoiceService.java`
- Test: `backend/src/test/java/com/fintech/api/service/InvoiceServiceTest.java` (estende; sem mudar contrato dos testes existentes)

### 2a. `findExistingForUpdate` — porta de entrada com lock

- [ ] Adicionar `findExistingForUpdate(Account, int, int) → Optional<Invoice>` (`@Transactional` —
      REQUIRED, mesma transação do caller). Delega ao novo finder do repository. Javadoc cita
      o invariante: "use quando a leitura precisa serializar com `close`/`pay` antes de gravar
      transação filha da fatura".

### 2b. `getOrCreate` — refatorar com lock + revalidação

- [ ] Dentro de `getOrCreate`, trocar o `findByAccountAndReferenceYearAndReferenceMonth(...)` por
      `findByAccountAndReferenceYearAndReferenceMonthForUpdate(...)` (mesma transação). Isso
      garante que `close`/`pay` concorrentes NÃO consigam alterar o status entre o read e o
      `save` da fatura.
- [ ] Quando a fatura EXISTE: revalidar `existing.getStatus() == OPEN`. Se NÃO (alguém fechou
      entre a nossa última leitura e este lock — improvável mas defensivo), lançar
      `BusinessException("A fatura de {mês/ano} está {status}; não é possível criar transações nela.")`
      — espelha a mensagem do `materializeFromRule` mas sem usar `BusinessConflictException`,
      porque a corrida não é estado observável pelo usuário (chega 500 se acontecer, e o cliente
      recebe a mensagem amigável). Decisão consciente: a probabilidade desta分支 é ~0 (lock
      adquirido antes), mas o guard documenta a intenção.
- [ ] Quando a fatura NÃO EXISTE: o caminho `self.createNewInvoice` em `REQUIRES_NEW` continua
      igual (retry do `DataIntegrityViolationException`). Lock pessimista não ajuda aqui porque
      a linha ainda não existe; o retry continua sendo o mecanismo de proteção.

### 2c. `close` — adquirir lock antes do set

- [ ] Trocar `loadByIdAndTenant(id, tenant)` por `loadByIdAndTenantForUpdate(id, tenant)` na
      primeira linha de `close`. O `loadByIdAndTenantForUpdate` delega ao
      `repository.findByIdAndLock(...)` com `@Lock(PESSIMISTIC_WRITE)`. Se a fatura estiver
      ausente, `EntityNotFoundException` (semântica atual preservada).
- [ ] Validar sob o lock: `if (invoice.getStatus() != InvoiceStatus.OPEN) { throw new IllegalStateException(...) }`
      — já existe, agora rodando após o lock (serializa com `pay` que também vai adquirir o
      lock no `markAsPaidIfClosed`).
- [ ] Teste: `InvoiceServiceTest.close` continua passando (cenário sem concorrência).

### 2d. `pay` — adquirir lock antes do claim

- [ ] Trocar `loadByIdAndTenant(id, tenant)` por `loadByIdAndTenantForUpdate(id, tenant)` na
      primeira linha de `pay`. O `markAsPaidIfClosed` que vem depois continua sendo a rede final
      (atomic UPDATE) — agora ele roda DEPOIS do lock pessimista, o que serializa a janela
      "duas pay() simultâneas veem CLOSED" completamente (lock + atomic UPDATE).
- [ ] Adicionar `findByIdForUpdate(UUID)` no `InvoiceRepository` (variante do `findById` com lock)
      se ainda não existir — usado pelo `loadByIdAndTenantForUpdate`.
- [ ] Teste: `InvoiceServicePaymentConcurrencyTest.concurrentPayCreatesSinglePayment` continua
      passando. Rodar **N vezes** se possível (CI flaky-ness do teste atual é o termômetro da
      regressão).

- [ ] Rodar: `./mvnw -f backend/pom.xml test -Dtest=InvoiceServiceTest,InvoiceServicePaymentConcurrencyTest`.

## Task 3 — `TransactionService`: usar variant locked

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/TransactionService.java`
- Test: `backend/src/test/java/com/fintech/api/service/TransactionServiceTest.java` (estende)

- [ ] Em `create(...)` (linha 211 do `TransactionService`): trocar `findExisting` por
      `findExistingForUpdate` quando `skipClosedInvoices && installments > 1`. **Por que sob
      lock:** a checagem precisa serializar com `close`/`pay` que também disputam o lock —
      sem ele, a janela de corrida original (#244) continua aberta.
- [ ] Logo após o `findExistingForUpdate` retornar fatura com status `!= OPEN`, manter o
      `continue` (descarte). A diferença é que agora o status é o ATUAL sob o lock, não o
      status que existia antes do lock — exatamente o que o #244 pede.
- [ ] Em `materializeFromRule(...)` (linha 343): mesma troca (`findExisting` → `findExistingForUpdate`).
      A diferença é que agora a recusa 409 reflete o status sob o lock, não o status pré-lock.
- [ ] Teste: o teste do invariante "soma das parcelas == total" (~linha 103 do
      `TransactionServiceTest`) continua passando (não tem concorrência; o caminho do lock
      é equivalente em single-thread).
- [ ] Rodar: `./mvnw -f backend/pom.xml test -Dtest=TransactionServiceTest`.

## Task 4 — Teste de concorrência dedicado (#244)

**Files:**
- Create: `backend/src/test/java/com/fintech/api/service/TransactionServiceParcelaInvoiceConcurrencyTest.java`

Modelo exato: `InvoiceServicePaymentConcurrencyTest.java`. Adaptações:
- [ ] `accountRepository.save(Account.builder().type(CREDIT_CARD)...)` + `CreditCardDetails`
      (closingDay=2, dueDay=10) para que `resolveInvoiceMonth` aterrisse na fatura M.
- [ ] Pré-condição: criar 1 fatura `OPEN` no mês M, sem transações filhas.
- [ ] 2 threads via `CyclicBarrier(2)`:
      - Thread A: `transactionService.create(parceledDto(... M ...), user)` (compra parcelada
        6x cuja primeira parcela cai em M).
      - Thread B: `invoiceService.close(invoiceM.getId(), tenant)`.
- [ ] **Assert pós-condição (não assert "qual ganhou"):**
      - `invoice.status` no fim é `CLOSED` (B ganhou OU A terminou antes de B pegar o lock);
      - Se A conseguiu gravar a parcela em M, `transaction.invoice.status == OPEN` (lock
        segurou — A terminou inteira antes de B entrar). Se A foi bloqueado e B fechou antes,
        A não deve ter gravado nada cuja `invoice.status == CLOSED`.
      - Forma robusta: contar transações filhas da fatura M **após o lock liberado**; se
        > 0, todas devem ter `invoice.status == OPEN` (não CLOSED). Zero é aceitável
        (B ganhou e A descartou corretamente).
- [ ] Repetir 30 iterações (mesmo padrão de #139).
- [ ] Limpar no `@AfterEach` (mesmo padrão RLS-aware: `SET LOCAL app.tenant_id` antes dos DELETEs).
- [ ] Rodar: `./mvnw -f backend/pom.xml test -Dtest=TransactionServiceParcelaInvoiceConcurrencyTest`
      3-5 vezes para confirmar que não é flaky.

## Task 5 — Documentação: remover a limitação conhecida

**Files:**
- Modify: `docs/superpowers/specs/2026-10-09-parcelas-somente-faturas-abertas-design.md`
- Modify: `summary.md`

- [ ] **Spec:** na seção "Fora de escopo" (linhas 213-219), **remover** o bullet "Corrida
      checagem×close/pay (limitação conhecida)" — agora resolvido pelo #244.
- [ ] **summary.md:** na seção de faturas (procurar a menção a "compra parcelada manual...
      que descartam/recusam parcela em fatura fechada", ~linha 136), adicionar uma frase
      positiva: "Garantia mantida sob concorrência: lock pessimista na fatura serializa
      `create`/`materializeFromRule` com `close`/`pay` (ver #244)."
- [ ] Commit: `docs(spec): remove limitação conhecida de corrida checagem×close/pay (fix #244)`
      e `docs(summary): documenta garantia de atomicidade sob concorrência (fix #244)`.

---

## Critério de conclusão (do issue #244)

- [ ] `InvoiceService.getOrCreate` e `close`/`pay` adquirem lock pessimista na fatura
      (`SELECT ... FOR UPDATE` via `@Lock(PESSIMISTIC_WRITE)`).
- [ ] `TransactionService.create` e `materializeFromRule` consultam o status sob o lock.
- [ ] Teste de concorrência cobre o interleaving: nenhuma parcela fica presa em fatura
      `CLOSED`/`PAID` após a corrida `create` × `close`/`pay`.
- [ ] `summary.md` e spec atualizados (limitação conhecida removida; garantia documentada).
- [ ] Suíte backend completa verde.
- [ ] SemVer: **PATCH** (sem mudança de contrato).

## Impacto SemVer

**PATCH** — nenhuma alteração em `api-spec/openapi.yaml`, nenhuma mudança observável no
contrato pelo cliente. O comportamento da fatura (descarte em `create` manual, 409 em
`materializeFromRule`) já era o alvo — só estávamos falhando em garantir sob concorrência.

## Notas de execução

- **Ordem importa:** Task 1 (finder) → Task 2 (InvoiceService) → Task 3 (TransactionService)
  → Task 4 (teste) → Task 5 (docs). O lock só funciona depois de todas as 3 alterações de
  código estarem em conjunto — a Task 1 sozinha não muda nada; a Task 2 sozinha deixa
  `TransactionService` ainda usando o caminho sem lock.
- **Trabalho em worktree única:** este plano inteiro cabe em uma só branch
  (`fix/244-race-condition-parcela-fatura`). Não há razão para subdividir — é um único
  invariante atômico.
- **Risco de deadlock:** lock pessimista em duas linhas diferentes na mesma ordem evita
  deadlock. Aqui só pegamos lock de UMA linha (a fatura disputada) por transação — não
  há cenário de duas linhas. Sem risco de deadlock por construção.
