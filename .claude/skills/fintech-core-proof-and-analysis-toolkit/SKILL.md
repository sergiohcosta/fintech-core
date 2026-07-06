---
name: fintech-core-proof-and-analysis-toolkit
description: >
  "Prove, não confie" — receitas de análise por primeiros princípios no fintech-core, cada
  uma com comando copy-paste e exemplo real do repo. Use quando a tarefa for: provar,
  verificar, auditar, demonstrar, "tem certeza?", isolamento de tenant, tenant leak,
  vazamento de tenant, conferir saldo, recomputar balance, invariante (soma de parcelas,
  centavos), race condition do getOrCreate de fatura, RRULE correto contra RFC 5545,
  validar projeção de recorrência, cross-check SQL vs API, ou desenhar um experimento
  hipótese → predição → medição. Não é para diagnosticar erro (debugging-playbook) nem
  para rodar a suíte de testes (validation-and-qa).
---

# Toolkit de Prova e Análise — fintech-core

Receitas para **demonstrar com evidência** que os invariantes do sistema valem — em vez de
confiar que valem porque "o código parece certo". O enunciado dos invariantes mora em
`fintech-core-architecture-contract`; aqui mora **como prová-los**.

Ferramentas usadas: `psql` via `docker exec` (container `fintech-postgres`, banco `fintech`,
usuário `admin` — ver `docker-compose.yml`), `curl` + `jq`, JUnit (`./mvnw -f backend/pom.xml
test -Dtest=Classe`) e `./scripts/test-summary.sh`. Credenciais e semântica do dataset Família
Costa: casa é `fintech-core-build-and-env` (os blocos de comando abaixo já usam a credencial de
seed no login) e a spec `docs/superpowers/specs/2026-06-09-test-dataset-design.md`. UUIDs usados abaixo
(verificados em `backend/src/main/resources/db/seed/V13__seed_dev.sql` e `V20__seed_dev_recurrence.sql`):

| Entidade | UUID |
|---|---|
| Tenant Família Costa | `10000000-0000-0000-0000-000000000001` |
| Carlos (ADMIN) | `20000000-0000-0000-0000-000000000001` |
| Conta Bradesco Corrente (CHECKING) | `30000000-0000-0000-0000-000000000001` |
| Conta Nubank (CREDIT_CARD, closingDay=2, dueDay=10) | `30000000-0000-0000-0000-000000000003` |
| Regra de recorrência Netflix (`FREQ=MONTHLY;BYMONTHDAY=15`, start 2026-01-15) | `90000000-0000-0000-0000-000000000001` |

> **Estado do banco dev:** o Postgres local pode conter dados além do seed (tenants pessoais
> pós `sync-db.sh`). Em 2026-07-05 este banco tinha 5 tenants. Por isso os critérios das
> receitas comparam **SQL vs API no mesmo banco**, nunca contra número fixo de seed.
>
> **Cerca de ambiente:** toda receita desta skill roda **somente contra o Postgres local do
> docker-compose** — nunca aponte SELECTs ou experimentos para Neon/Railway. Atenção: com
> `neon.enabled=true` o `NeonFallbackEnvironmentPostProcessor` pode fazer um backend
> `localhost:8080` de pé escrever no banco remoto (ver `fintech-core-config-and-flags`) —
> confirme o datasource antes de qualquer receita que crie dados via API.

---

## 0. Método geral: hipótese → predição → comando → comparação

Toda prova aqui segue o mesmo ciclo curto:

1. **Hipótese** — enuncie o invariante ("o saldo da conta X é a soma das transações PAID").
2. **Predição numérica ANTES de rodar** — escreva o número (ou "0 linhas") que você espera.
   Predição depois do resultado não é prova, é racionalização.
3. **Comando** — uma medição independente do caminho de produção (SQL cru quando o código
   usa JPQL; expansão manual quando o código usa lib-recur).
4. **Comparação** — igual até o centavo/dia ⇒ invariante demonstrado *neste estado do banco*;
   diferente ⇒ você achou um bug ou uma premissa errada. Ambos são resultado útil.

Duas medições pelo **mesmo caminho** (ex: chamar a API duas vezes) não provam nada — a
segunda medição precisa ser por caminho independente.

---

## 1. Provar isolamento de tenant em nível de query

### 1a. Inventário automatizado dos repositories

**Objetivo:** listar quais repositories têm (ou não) escopo de tenant declarado, para revisão dirigida.

**Pré-requisitos:** nenhum (só o repo).

**Passos:**
```bash
# Repositories SEM nenhuma menção a tenant (candidatos a revisão manual):
grep -L -i tenant /home/sergio/fintech-core/backend/src/main/java/com/fintech/api/repository/*.java

# Densidade de escopo por arquivo (quantas vezes 'tenant' aparece):
grep -c -i tenant /home/sergio/fintech-core/backend/src/main/java/com/fintech/api/repository/*.java

# Métodos escopados explicitamente (finders derivados + params JPQL):
grep -n "ByTenant\|:tenant" /home/sergio/fintech-core/backend/src/main/java/com/fintech/api/repository/*.java
```

**Saída esperada (verificada em 2026-07-05):** o primeiro comando lista exatamente 4 arquivos:
`BudgetItemRepository.java`, `CreditCardDetailsRepository.java`, `InvoiceRepository.java`,
`RecurrenceExceptionRepository.java`.

**Interpretação:** ausência de `tenant` no repository **não é automaticamente um leak** —
esses 4 são escopados **indiretamente pelo agregado pai**: `CreditCardDetails.findByAccount`
e `Invoice.findByAccount...` recebem uma `Account` que o service já resolveu com
`findByIdAndTenant`; `RecurrenceException` é escopada pela `RecurrenceRule`; `BudgetItem`
pelo `BudgetCycle`/`Transaction`. O que o inventário compra é a **lista curta de lugares onde
a prova depende do service**, e é nesses services que a revisão manual deve confirmar que o
pai foi carregado com escopo. Um repository novo que apareça nesta lista sem justificativa
de agregado pai é um alarme real.

### 1b. Teste de integração cross-tenant

**Objetivo:** provar em teste executável que recurso do tenant A não é visível autenticado como tenant B.

**Pré-requisitos:** Postgres dev de pé (os testes de integração do projeto rodam contra ele, sem Testcontainers).

**Passos:** o projeto **já tem** provas desse tipo — rode-as e use-as de modelo:

```bash
# Nível repository (integração real contra Postgres, rollback via @Transactional):
./mvnw -f /home/sergio/fintech-core/backend/pom.xml test -Dtest=RecurrenceRuleRepositoryTest

# Nível controller (MockMvc + spring-security-test; service mockado lança EntityNotFoundException → 404):
./mvnw -f /home/sergio/fintech-core/backend/pom.xml test -Dtest=RecurrenceOccurrenceControllerTest
```

Classes reais para citar/copiar (verificadas em 2026-07-05):
- `backend/src/test/java/com/fintech/api/repository/RecurrenceRuleRepositoryTest.java` —
  método `naoVazaRegraEntreTenants()`: persiste regra no tenant dono, consulta com tenant
  intruso, espera `findByIdAndTenant(...)` vazio.
- `backend/src/test/java/com/fintech/api/controller/RecurrenceOccurrenceControllerTest.java` —
  método `confirmaOutroTenant404()`: `POST .../confirm` autenticado espera `status().isNotFound()`.
- `backend/src/test/java/com/fintech/api/service/AccountServiceTest.java:98` — variante unitária
  ("findById lança EntityNotFoundException para conta de outro tenant").

Esqueleto para um domínio ainda sem essa prova (estilo do `RecurrenceRuleRepositoryTest`):

```java
@SpringBootTest
@Transactional // rollback automático: nada é commitado no banco dev
class MinhaEntidadeIsolamentoTest {
    @Autowired MinhaEntidadeRepository repository;
    @Autowired TenantRepository tenantRepository;

    @Test
    void naoVazaEntreTenants() {
        Tenant dono = tenantRepository.save(novoTenant("Dono"));
        Tenant intruso = tenantRepository.save(novoTenant("Intruso"));
        var recurso = repository.save(fixture(dono));

        assertThat(repository.findByIdAndTenant(recurso.getId(), intruso)).isEmpty();
    }
}
```

**Saída esperada:** `BUILD SUCCESS`, testes verdes.

**Interpretação:** verde prova o isolamento **para os finders exercitados**. A prova é por
método de repository, não global — combine com 1a para saber quais métodos ainda não têm teste.

### 1c. SQL direto no banco dev — coerência de FKs de tenant

**Objetivo:** provar que nenhuma linha de `transactions` aponta para conta de outro tenant (dado, não código).

**Pré-requisitos:** `docker compose up -d` (container `fintech-postgres`).

**Passos:**
```bash
docker exec fintech-postgres psql -U admin -d fintech -c "
SELECT t.id, t.description, t.tenant_id AS tx_tenant, a.tenant_id AS account_tenant
FROM transactions t
JOIN accounts a ON a.id = t.account_id
WHERE t.tenant_id <> a.tenant_id;"
```

**Saída esperada:** `(0 rows)` — **verificado ao vivo em 2026-07-05** no banco dev (5 tenants presentes).

**Interpretação:** 0 linhas = nenhum registro cruzado *hoje*, o que fecha o argumento: mesmo
que uma query esquecesse o filtro de tenant, não haveria linha órfã cruzada para vazar por
FK inconsistente. Linhas retornadas = vazamento de dados já materializado — o bug mais grave
possível neste projeto; pare tudo e roteie pelo `fintech-core-change-control`. A mesma forma
de query serve para `budget_items ↔ budget_cycles`, `invoices ↔ accounts`, etc.

**Variante HTTP (com backend de pé):** registre um tenant descartável e tente ler a conta do
Carlos — deve dar **404** (não 403: o sistema não revela existência). Cria dados no banco dev —
use só em dev, e só com o backend apontando para o Postgres local (cerca de ambiente acima). Saída esperada derivada do contrato (backend estava offline na validação de 2026-07-05):

```bash
TOKEN_B=$(curl -s -X POST http://localhost:8080/auth/register -H 'Content-Type: application/json' \
  -d '{"name":"Tenant Descartável","adminName":"Intruso","adminEmail":"intruso@teste.com","password":"Senha123x"}' | jq -r .token)
curl -s -o /dev/null -w '%{http_code}\n' \
  http://localhost:8080/api/accounts/30000000-0000-0000-0000-000000000001 \
  -H "Authorization: Bearer $TOKEN_B"   # esperado: 404
```

---

## 2. Verificar correção do cálculo de saldo

### 2a. Saldo de conta: SQL independente vs `GET /api/accounts/{id}`

**Objetivo:** recomputar o `balance` da conta por SQL cru e comparar com a API até o centavo.

**Pré-requisitos:** Postgres + backend de pé (`curl http://localhost:8080/actuator/health`), `jq`.

**Passos:**
```bash
# 1. Predição independente — mesma semântica do JPQL calculateBalance (AccountRepository):
#    SUM(±amount) das transações PAID da conta, sem filtro de período.
docker exec fintech-postgres psql -U admin -d fintech -Atc "
SELECT COALESCE(SUM(CASE WHEN type='INCOME' THEN amount ELSE -amount END),0)
FROM transactions
WHERE account_id='30000000-0000-0000-0000-000000000001' AND status='PAID';"

# 2. Medição pela API:
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"carlos@costa.com","password":"costa123"}' | jq -r .token)
curl -s http://localhost:8080/api/accounts/30000000-0000-0000-0000-000000000001 \
  -H "Authorization: Bearer $TOKEN" | jq .balance
```

**Saída esperada:** os dois números idênticos até o centavo. Em 2026-07-05 o passo 1 retornou
`23248.10` neste banco dev (valor muda com o estado do banco — o critério é a igualdade, não
o número). Passo 2 derivado do contrato (backend offline na validação).

**Interpretação:** iguais ⇒ o JPQL `calculateBalance` e a serialização do DTO preservam a
semântica "saldo = só PAID" (teoria em `fintech-domain-reference`). Diferentes ⇒ suspeite de
transação com `status` inesperado, ou drift entre JPQL e esta query — bissecte por
`type` (`GROUP BY type, status`) até achar a linha divergente.

### 2b. `totalAccountBalance` do dashboard

**Objetivo:** mesma prova para o agregado do dashboard, que soma só contas `count_in_liquid_balance=true`.

**Passos:**
```bash
# Predição — réplica SQL do JPQL sumNetLiquidBalanceByTenant (TransactionRepository):
docker exec fintech-postgres psql -U admin -d fintech -Atc "
SELECT COALESCE(SUM(CASE WHEN t.type='INCOME' THEN t.amount ELSE -t.amount END),0)
FROM transactions t JOIN accounts a ON a.id = t.account_id
WHERE t.tenant_id='10000000-0000-0000-0000-000000000001'
  AND t.status='PAID' AND a.count_in_liquid_balance = true;"

# Medição — atenção: o parâmetro é `month` (openapi.yaml + DashboardController), não `period`:
curl -s "http://localhost:8080/api/dashboard/summary?month=2026-07" \
  -H "Authorization: Bearer $TOKEN" | jq .totalAccountBalance
```

**Saída esperada:** idênticos. Em 2026-07-05 o SQL retornou `22683.10` neste banco dev.
Note que `totalAccountBalance` **não** tem filtro de período — trocar `month` não deve mudá-lo
(boa segunda predição de graça).

**Interpretação:** divergência aqui com 2a verde aponta para o flag `count_in_liquid_balance`
(ex: cartão contando como caixa) — confira `SELECT name, count_in_liquid_balance FROM accounts
WHERE tenant_id='10000000-...-0001'`. Detalhe verificado no código: este JPQL não filtra
`a.active` (diferente do `sumLiquidBalanceByTenant` de opening balance) — contas arquivadas
com histórico PAID ainda contam aqui. Se a sua predição incluiu `a.active=true`, ela diverge
do comportamento atual — que é escrito assim de propósito no código, mas é exatamente o que a
issue **#151 (OPEN)** trata como bug a decidir (contas arquivadas ainda contam); ver
`fintech-core-bug-backlog-campaign` fase 3.3. Não trate nenhum dos dois lados como veredicto.

---

## 3. Demonstrar a race condition do `getOrCreate` de fatura (histórica — resolvida)

**Objetivo:** provar que N criações simultâneas da mesma fatura produzem exatamente 1 linha —
a proteção da issue #83 (fechada; ADR-001) via `UNIQUE(account_id, reference_year, reference_month)`
+ `@Transactional(REQUIRES_NEW)` + retry no catch de `DataIntegrityViolationException`
(`InvoiceService.getOrCreate`, linhas 49–64, e `createNewInvoice`, linha 68 — verificado 2026-07-05).

**Pré-requisitos:** Postgres dev de pé. Já existe teste unitário do retry
(`InvoiceServiceTest.getOrCreateRetriesOnRaceCondition`, com repository mockado); o teste de
concorrência REAL abaixo **não existe hoje** — é o esqueleto proposto.

**Passos:**
```java
// Estilo RecurrenceRuleRepositoryTest (integração contra o Postgres dev), MAS sem
// @Transactional na classe: REQUIRES_NEW commita em transações próprias por thread —
// rollback do teste não as desfaz. Limpeza manual obrigatória no finally.
@SpringBootTest
class InvoiceGetOrCreateConcurrencyTest {

    @Autowired InvoiceService invoiceService;
    @Autowired InvoiceRepository invoiceRepository;
    @Autowired AccountRepository accountRepository;

    @Test
    void nCriacoesSimultaneasProduzemUmaUnicaFatura() throws Exception {
        // Nubank do seed (CREDIT_CARD — createNewInvoice exige creditCardDetails).
        Account nubank = accountRepository
                .findById(UUID.fromString("30000000-0000-0000-0000-000000000003")).orElseThrow();
        int n = 8;
        var pool = Executors.newFixedThreadPool(n);
        var largada = new CountDownLatch(1);
        List<Future<Invoice>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++)
            futures.add(pool.submit(() -> { largada.await(); return invoiceService.getOrCreate(nubank, 2031, 1); }));
        largada.countDown(); // todas as threads batem no getOrCreate "ao mesmo tempo"

        Set<UUID> ids = new HashSet<>();
        try {
            for (var f : futures) ids.add(f.get().getId());
            assertThat(ids).hasSize(1); // todas receberam a MESMA fatura, nenhuma exceção vazou
        } finally {
            pool.shutdown();
            ids.forEach(invoiceRepository::deleteById); // 2031-01 não colide com dados reais
        }
    }
}
```
Auditoria SQL do invariante no banco (esperado 0 linhas — a UNIQUE impede novas duplicatas):
```bash
docker exec fintech-postgres psql -U admin -d fintech -c "
SELECT account_id, reference_year, reference_month, COUNT(*)
FROM invoices GROUP BY 1,2,3 HAVING COUNT(*) > 1;"
```

**Saída esperada:** teste verde; auditoria `(0 rows)`.

**Interpretação — assinaturas de regressão** (o que você observaria se a proteção regredisse):
- **Retry removido** (catch do `DataIntegrityViolationException` some): as N−1 threads
  perdedoras estouram `DataIntegrityViolationException` → era exatamente o 500 intermitente
  da issue #83 ao criar parcelas em paralelo.
- **REQUIRES_NEW removido** (ou chamada em `this` em vez do proxy `self` — ver comentário nas
  linhas 43–47 do `InvoiceService`): o conflito marca a transação externa como rollback-only e
  o retry dentro dela falha com `UnexpectedRollbackException`.
- **UNIQUE derrubada na migration**: o teste passa a ver `ids.size() > 1` e a auditoria SQL
  retorna linhas — duplicata silenciosa, pior cenário.

---

## 4. Provar o invariante de soma de parcelas (violado em código — issue #136)

**Objetivo:** auditar `SUM(parcelas) == installment_groups.total_amount` em todo o banco.

**Contexto (verificado 2026-07-05):** a issue **#136 está OPEN** — `TransactionService.create`
divide `amount.divide(N, 2, HALF_EVEN)` e dá o **mesmo** valor às N parcelas; nenhuma absorve
o resto. R$100 em 3x ⇒ 3×33,33 = 99,99 ≠ 100,00. O invariante está violado **no código**;
o banco só mostra a violação quando alguém cria um parcelamento cujo total não divide exato.

**Pré-requisitos:** Postgres dev de pé.

**Passos:**
```bash
# Detector da violação (a query que a issue #136 pede como critério de correção):
docker exec fintech-postgres psql -U admin -d fintech -c "
SELECT g.id, g.description, g.total_amount, SUM(t.amount) AS soma_parcelas,
       SUM(t.amount) - g.total_amount AS delta
FROM installment_groups g
JOIN transactions t ON t.installment_group_id = g.id
GROUP BY g.id, g.description, g.total_amount
HAVING SUM(t.amount) <> g.total_amount;"

# Anomalia irmã — grupos órfãos (0 parcelas vinculadas), que o JOIN acima não enxerga:
docker exec fintech-postgres psql -U admin -d fintech -c "
SELECT g.id, g.description, g.total_amount
FROM installment_groups g LEFT JOIN transactions t ON t.installment_group_id = g.id
GROUP BY g.id HAVING COUNT(t.id) = 0;"
```

**Saída esperada (medida ao vivo em 2026-07-05):** detector = `(0 rows)` — os grupos atuais
do banco dev têm totais que dividem exato; órfãos = 1 linha (`Ração Floki`, 400.00 em 2x,
0 parcelas). Ou seja: **hoje o dado não exibe a violação de centavos, mas o código a produz**
sob demanda — crie via API um parcelamento de 100.00 em 3x e rode o detector de novo:
esperado 1 linha com `delta = -0.01`.

**Interpretação:** este é o exemplo canônico de "banco limpo ≠ código correto". A prova
completa do bug é o par (predição: 3×33,33=99,99) + (medição: detector acusa delta após a
criação). Após a correção (#136: última parcela = `total − (N−1)×parcela`), o detector deve
permanecer em 0 linhas para qualquer (total, N). Não corrija dados na mão — correção roteia
pelo `fintech-core-change-control`; o backlog do bug vive em `fintech-core-bug-backlog-campaign`.

---

## 5. Verificar expansão RRULE contra a RFC 5545

**Objetivo:** validar que a projeção de recorrência (lib-recur via `RecurrenceExpander`) gera
exatamente as datas que uma leitura manual da RFC 5545 prediz.

**Material real:** regra Netflix do seed V20 — `FREQ=MONTHLY;BYMONTHDAY=15`, `start_date=2026-01-15`,
com **EXDATE 2026-02-15** em `recurrence_exceptions` (ambos confirmados por SELECT em 2026-07-05;
zero ocorrências materializadas hoje: `SELECT * FROM transactions WHERE recurrence_rule_id='90000000-...-0001'` vazio).

**Passos:**

1. **Predição manual** (escreva antes): janela 2026-01-01..2026-12-31 ⇒ dia 15 de cada mês
   = 12 datas, menos EXDATE 15/02, menos materializadas (0) ⇒ **11 fantasmas**:
   15/01, 15/03, 15/04, ..., 15/12.

2. **Medição via API** (fantasmas têm `projected=true`, `id=null`, `recurrenceRuleId` e
   `occurrenceDate` preenchidos — nomes conforme `summary.md`):
```bash
curl -s "http://localhost:8080/api/transactions?includeProjected=true&startDate=2026-01-01&endDate=2026-12-31" \
  -H "Authorization: Bearer $TOKEN" \
  | jq '[.[] | select(.projected == true and .recurrenceRuleId == "90000000-0000-0000-0000-000000000001")
         | .occurrenceDate]'
```
   Esperado (derivado do seed; backend offline na validação): array com as 11 datas acima,
   **sem** `2026-02-15`.

3. **Caso BYMONTHDAY=-1 (fim do mês)** — o teste unitário real já cobre a aritmética contra
   predição manual, incluindo fevereiro bissexto:
```bash
./mvnw -f /home/sergio/fintech-core/backend/pom.xml test -Dtest=RecurrenceExpanderTest
```
   `ultimoDiaDoMesAncoraNoDiaValido()`: `FREQ=MONTHLY;BYMONTHDAY=-1` a partir de 2024-01-31
   ⇒ 2024-02-**29** (bissexto), 2024-03-31, 2024-04-30. Predição manual para 2026 (não
   bissexto): fevereiro ⇒ 2026-02-**28**. Subconjunto RRULE suportado e rejeições (`BYDAY`,
   `BYSETPOS`...) em `summary.md`; `aceitaSubconjuntoERejeitaForaDele()` prova as rejeições.

4. **Prova do índice único parcial** (dupla confirmação ⇒ 409). Mutação: materializa uma
   transação no banco dev — somente contra o Postgres local (cerca de ambiente acima, nunca
   Neon/Railway); desfaça depois deletando-a pela API/SQL, ou prefira a versão
   MockMvc já existente (`RecurrenceOccurrenceControllerTest.confirmaDuplicada409`):
```bash
URL="http://localhost:8080/api/recurrence-rules/90000000-0000-0000-0000-000000000001/occurrences/2026-08-15/confirm"
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$URL" -H "Authorization: Bearer $TOKEN"  # esperado: 201
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$URL" -H "Authorization: Bearer $TOKEN"  # esperado: 409
```

**Interpretação:** as 11 datas batendo provam a cadeia inteira — parse RRULE, expansão na
janela, subtração de materializadas e de EXDATEs (`fantasma = expand − materializadas − EXDATE`).
O 409 na segunda confirmação prova que o guard não é só código: o índice único parcial
`(recurrence_rule_id, recurrence_occurrence)` (V19, ver `database-schema.md`) segura até
concorrência. Divergência de datas: bissecte — expansão pura (`RecurrenceExpanderTest`) verde
⇒ o problema está na subtração (EXDATE/materializadas), não na aritmética RFC 5545.

---

## Quando NÃO usar esta skill

| Situação | Use em vez |
|---|---|
| Há um **sintoma de erro** (exceção, 500, teste vermelho, comportamento estranho) | `fintech-core-debugging-playbook` — triagem sintoma→causa |
| Quer **rodar a suíte** / cobertura / CI / SonarQube como evidência padrão | `fintech-core-validation-and-qa` |
| A prova encontrou um bug e você vai **corrigi-lo** | `fintech-core-change-control` (nenhuma correção por fora) |
| Precisa da **teoria** (por que saldo só conta PAID, ciclo de fatura, Modelo A) | `fintech-domain-reference` |
| Precisa do **enunciado** dos invariantes e do porquê | `fintech-core-architecture-contract` |
| Vai atacar o backlog de bugs 2026-07 (#135–#152) de forma sistemática | `fintech-core-bug-backlog-campaign` |

---

## Proveniência e manutenção

Verificado contra o repo e o banco dev em **2026-07-05**. Executados ao vivo (somente SELECT):
coerência de FKs (1c: 0 linhas), recompute de saldos (2a: 23248.10; 2b: 22683.10), auditoria
de parcelas (4: detector 0 linhas, 1 grupo órfão), estado da regra Netflix (5: 0 materializadas,
EXDATE 2026-02-15). Backend estava offline: saídas de `curl` marcadas como derivadas do
contrato. Issue #136 OPEN, #83 CLOSED na data.

Re-verificação de uma linha por fato volátil:
- Container/credenciais do banco: `grep -A6 'postgres:' /home/sergio/fintech-core/docker-compose.yml`
- Repos sem menção a tenant: `grep -L -i tenant /home/sergio/fintech-core/backend/src/main/java/com/fintech/api/repository/*.java`
- Retry do getOrCreate segue no lugar: `grep -n 'REQUIRES_NEW\|DataIntegrityViolation' /home/sergio/fintech-core/backend/src/main/java/com/fintech/api/service/InvoiceService.java`
- Bug de centavos segue aberto: `gh issue view 136 --json state -q .state`
- Divisão de parcelas ainda uniforme: `grep -n 'divide(BigDecimal.valueOf' /home/sergio/fintech-core/backend/src/main/java/com/fintech/api/service/TransactionService.java`
- Regra Netflix do seed: `grep -n 'FREQ=MONTHLY' /home/sergio/fintech-core/backend/src/main/resources/db/seed/V20__seed_dev_recurrence.sql`
- Param do dashboard (`month`, não `period`): `grep -n -A3 '/api/dashboard/summary' /home/sergio/fintech-core/api-spec/openapi.yaml`
