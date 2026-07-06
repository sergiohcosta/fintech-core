---
name: fintech-core-debugging-playbook
description: >
  Playbook de depuração do fintech-core: tabela sintoma→triagem dos modos de falha reais do
  projeto e histórico das batalhas resolvidas. Use quando encontrar erro, exception, falha de
  teste ou comportamento estranho — checksum mismatch do Flyway, backend não sobe, migration
  falha, LazyInitializationException, N+1, transação de cartão sumindo do dashboard (INNER JOIN
  implícito), 500 em vez de 404, 401/429 no login, erro de CORS, botão desabilitado que não
  reage (Zoneless/Signals, form.invalid), computed que não recalcula, loop de effect, build do
  frontend quebrado após regenerar cliente Orval (auth.service.ts), campos opcionais inesperados,
  springdoc/Swagger quebrado, specs Vitest falhando com npx vitest, suíte backend travada,
  paths duplicados backend/backend, SonarQube "Insufficient privileges". Diagnóstico apenas —
  correções de comportamento roteiam para fintech-core-change-control.
---

# Playbook de Depuração — fintech-core

Este é o mapa dos modos de falha **reais** deste repositório — cada um já custou tempo de
sessão. Fluxo de uso: localize o sintoma na tabela de triagem, rode o **experimento
discriminante** (um comando que separa a hipótese certa das parecidas), e só então corrija.
As histórias completas estão em "Batalhas resolvidas", no fim.

Contexto mínimo: backend Java 21 / Spring Boot 4.0.1 em `backend/`, frontend Angular 21
Zoneless em `frontend/`, PostgreSQL 16 no Docker (container `fintech-postgres`, db `fintech`,
user `admin`, senha `secret`), migrations Flyway V1–V21 (em 2026-07-04).

## Quando NÃO usar esta skill

- **Corrigir o bug depois de diagnosticado** (nova migration, mudança de query, novo endpoint):
  siga `fintech-core-change-control` — aqui só se chega ao diagnóstico.
- **Provar um invariante** (isolamento de tenant, soma de parcelas, expansão RRULE):
  `fintech-core-proof-and-analysis-toolkit`.
- **Ambiente que nunca funcionou** (setup do zero, versões de toolchain, pipeline Orval
  completo): `fintech-core-build-and-env`.
- **Atacar o backlog de bugs da auditoria 2026-07 (#135–#152)**: `fintech-core-bug-backlog-campaign`.

---

## Tabela de triagem: sintoma → suspeita → experimento discriminante

### Flyway / banco

| Sintoma | Suspeita | Experimento discriminante |
|---|---|---|
| Backend não sobe; log com `Migration checksum mismatch for migration version X` | Migration ou seed **já aplicado** foi editado (checksum gravado ≠ arquivo atual) | `git log --oneline -5 -- backend/src/main/resources/db/` — se o arquivo VX mudou depois de aplicado, é isso. Confirme o checksum gravado: `docker exec fintech-postgres psql -U admin -d fintech -c "SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10"` |
| Testes de integração falham com config faltando (datasource, token secret, CORS...) que funciona no `spring-boot:run` | `src/test/resources/application-dev.properties` **substitui** (não faz merge com) o de `src/main/resources` | `diff backend/src/main/resources/application-dev.properties backend/src/test/resources/application-dev.properties` — toda prop necessária tem que existir nos DOIS |
| Testes de integração reclamam de migration `missing`/`future` (V13, V16, V18, V20...) | Seeds vivem em `db/seed` (fora do classpath de teste) mas estão registrados no `flyway_schema_history` do banco compartilhado | Confira `spring.flyway.ignore-migration-patterns=*:missing,*:future` em `backend/src/test/resources/application-dev.properties` — deve estar lá |
| `duplicate key value violates unique constraint` em `invoices` sob requisições concorrentes | Race do `getOrCreate` de fatura — **já resolvida** com `REQUIRES_NEW` + retry (ADR-001, issue #83) | Se reapareceu, confira `@Transactional(propagation = Propagation.REQUIRES_NEW)` em `InvoiceService.createNewInvoice` e o catch/retry em `getOrCreate`. Receita de reprodução: `fintech-core-proof-and-analysis-toolkit` |

**Regra fixa**: checksum mismatch NUNCA se corrige editando o arquivo de volta nem apagando
linha do `flyway_schema_history` — a correção é **nova migration/seed de versão superior**
(foi assim que o V18 corrigiu o V16). Como criar a migration: `fintech-core-change-control`.

### Hibernate / JPA

| Sintoma | Suspeita | Experimento discriminante |
|---|---|---|
| Dashboard/summary "perde" transações que existem no banco (tipicamente as **sem** fatura) | `t.invoice.dueDate` referenciado direto no WHERE gera **INNER JOIN implícito** — linhas com `invoice IS NULL` somem | Ligue `spring.jpa.show-sql=true` (já ativo no perfil dev) e procure `inner join invoices` no SQL gerado. Padrão correto: `LEFT JOIN t.invoice inv` + `(inv IS NOT NULL AND inv.dueDate BETWEEN ...) OR (inv IS NULL AND t.date BETWEEN ...)` — ver `TransactionRepository.sumByTenantAndTypeAndPeriod` |
| `LazyInitializationException` ao ler campo do usuário autenticado (principal) fora do request inicial | Principal é entidade **detached** (carregada no `SecurityFilter`, antes da OSIV) | O acesso que quebra é a algo além de `getId()`? De proxy LAZY do principal só é seguro ler `.getId()` ou passá-lo como parâmetro JPQL. O `UserRepository.findByEmail` já faz `JOIN FETCH u.tenant` — se você adicionou associação nova ao User, ela precisa do mesmo tratamento |
| Endpoint devolve **500** onde deveria ser **404** (`entidade não encontrada`) | Service lançou `jakarta.persistence.EntityNotFoundException` (infra) em vez de `com.fintech.api.exception.EntityNotFoundException` (domínio) | `grep -rn "jakarta.persistence.EntityNotFoundException" backend/src/main/java` — deve retornar **vazio**. O `GlobalExceptionHandler` só mapeia a do pacote `com.fintech.api.exception` para 404 |
| Query JPQL com parâmetro de data opcional retorna vazio ou lança erro quando o filtro não é enviado | Param `null` em `BETWEEN`/comparação de data | Padrão da casa: sentinelas `LocalDate.of(1000, 1, 1)` / `LocalDate.of(9999, 12, 31)` no service (ver `TransactionService`, ~linhas 74–75) em vez de `IS NULL OR` na query |

### Zoneless / Signals (frontend)

Não existe `zone.js` neste projeto (`provideZonelessChangeDetection()`): **nada re-renderiza
"sozinho"** — só mudança de Signal dispara change detection.

| Sintoma | Suspeita | Experimento discriminante |
|---|---|---|
| `[disabled]="form.invalid"` (ou `form.valid` no template) não atualiza ao digitar | `FormControl`/`FormGroup` não é Signal; sem zone.js ninguém avisa o template | Troque por `toSignal(this.form.statusChanges, { initialValue: this.form.status })` e derive `computed()`. Se passar a reagir, era isso |
| `computed()` que lê `control.value` nunca recalcula | Mesmo motivo: `.value` é leitura imperativa, não rastreável | Bridge com `toSignal(control.valueChanges, { initialValue: control.value })` — padrão real em `frontend/src/app/features/transaction/transaction-form/transaction-form.ts` (linhas 113–115) |
| Loop infinito de `effect()` ou erro NG0600 (write em contexto reativo) | Handler/effect que **lê** um signal e depois chama loader que **escreve** signals | Envolva a chamada em `untracked(() => this.loadTransactions(...))` — padrão real em `transaction-list.ts` (linhas 100, 239, 253) |

### Orval / codegen / contrato

Sintomas e diagnóstico aqui; o pipeline completo de regeneração é casa da
`fintech-core-build-and-env` (atalho: `./scripts/api-sync.sh`).

| Sintoma | Suspeita | Experimento discriminante |
|---|---|---|
| Build/testes do frontend quebram em `core/api/auth/` logo após regenerar o cliente | Orval regenerou `auth/auth.service.ts`, que deve ser **deletado** (há implementação própria) | `git status frontend/src/app/core/api/` — se `auth/auth.service.ts` aparece como novo/modificado, delete: `rm -f frontend/src/app/core/api/auth/auth.service.ts`. O `./scripts/api-sync.sh` já faz isso (passo 4/4) — se quebrou, alguém rodou os passos manualmente |
| Campo que "sempre vem" chega tipado como opcional (`foo?:`) e força `!` no código | Schema de **resposta** sem bloco `required:` em `api-spec/openapi.yaml` | `grep -A5 "NomeDoSchema:" api-spec/openapi.yaml` — sem `required:`, o Orval gera tudo opcional. Corrigir a spec = mudança de contrato → `fintech-core-change-control` |
| Backend não sobe ou Swagger UI quebra após mexer em dependências | springdoc incompatível — este projeto **exige 2.8.9** (2.6.0 não funciona com Spring Boot 4.0.1) | `grep -A2 springdoc backend/pom.xml` — a versão deve ser `2.8.9` |

### JWT / CORS / autenticação

| Sintoma | Suspeita | Experimento discriminante |
|---|---|---|
| Login devolve **401** com credenciais que "deveriam" funcionar | Resposta é **genérica de propósito** (anti-enumeração): email inexistente, senha errada e usuário **inativo** dão o mesmo 401 | `docker exec fintech-postgres psql -U admin -d fintech -c "SELECT email, active FROM users WHERE email = 'x@y.com'"` — se `active = false`, é isso, não senha errada |
| Login devolve **429 Too Many Requests** | `LoginRateLimiter` em memória: 5 tentativas falhas por email / 60 s (defaults `security.rate-limit.max-attempts` e `.window-seconds`) | Espere 1 minuto ou reinicie o backend (estado é em memória). Não é bug — é feature das issues #110–#115 |
| Browser bloqueia chamada com erro de CORS | Origem fora de `cors.allowed-origins` (dev: só `http://localhost:4200`; prod: env `CORS_ALLOWED_ORIGINS`) | `grep -rn "cors.allowed-origins" backend/src/main/resources/` — frontend servido em outra porta/host precisa entrar na lista (mudança → change-control) |
| Toda requisição autenticada dá 401/403 de repente | Token expirado, ou usuário desativado (o `SecurityFilter` checa `isEnabled()` em **toda** requisição, não só no login) | Procure no log do backend os WARN do `SecurityFilter` ("Token inválido ou expirado" / "usuário está inativo") — cada caso loga mensagem distinta |

### Gotchas operacionais de agente

Regras extraídas de auditoria de sessões reais — cada uma causou loop de tentativa-erro:

| Sintoma | Causa | Ação correta |
|---|---|---|
| Specs de **componente** Angular explodem com erros estranhos de ambiente | Rodou `npx vitest` cru — specs de componente só funcionam dentro do builder Angular | `cd frontend && npm test` (ou `ng test --watch=false`), ou `./scripts/test-summary.sh frontend`. Lógica pura (`*-utils.ts`, sem imports Angular) roda em qualquer runner |
| Suíte backend "travada"/sessão bloqueada | A suíte completa demora **>7 min** (Testcontainers) | Rode em background, ou `./scripts/test-summary.sh backend`, ou restrinja: `./mvnw -f backend/pom.xml test -Dtest=ClasseEspecifica` |
| `No such file or directory: backend/backend/...` ou git agindo no repo errado | O cwd **não persiste** entre comandos Bash da sessão | Sempre paths absolutos: `git -C /caminho/da/worktree ...` e `./mvnw -f backend/pom.xml ...`. Nunca prefixar um path já relativo ao cwd |
| Plano SDD trava na task 3 porque "o teste já falhava antes" | Baseline não estava verde ao iniciar | Baseline verde é pré-condição de plano SDD — regra completa e racional: `fintech-core-validation-and-qa` |
| SonarQube MCP retorna `Insufficient privileges` para quality gate/medidas | O MCP só tem permissão de **ler issues** | Não re-tente o MCP: gate e medidas via `curl` com `SONAR_TOKEN` do `.env` (token `sqa_`). A skill de projeto `sonar-status` já encapsula isso |

---

## Batalhas resolvidas

Histórico das armadilhas que custaram tempo real. Se um sintoma da tabela reaparecer, a
história diz onde a solução mora e por quê.

### 1. O seed editado que derrubou o backend (checksum Flyway)

O seed de planejamento (`V16__seed_dev_budget.sql`) tinha um `opening_balance` errado
(1200 em vez de 18123.10 — o valor não refletia o cálculo date-bounded do ciclo). O reflexo
natural — editar o V16 — quebra o checksum do Flyway em todo banco onde ele já rodou: o
backend simplesmente não sobe mais. A correção correta foi **nova versão**:
`V18__fix_dev_budget_opening_balance.sql` faz o `UPDATE` por cima. Desde então a regra é
inviolável: seed aplicado é migration aplicada — imutável. (O V17 é outro exemplo do mesmo
padrão: corrigiu `reference_year`/`reference_month` de faturas legadas, sequela de um bug no
`resolveInvoiceMonth`, via migration nova em vez de retocar dados na mão.)

### 2. O `application-dev.properties` fantasma (test-classpath do Flyway)

Testes de integração falhavam com configurações que funcionavam perfeitamente no
`spring-boot:run`. Causa: quando existe `src/test/resources/application-dev.properties`, ele
**substitui integralmente** o de `src/main/resources` — Spring não faz merge entre
classpaths. Toda prop necessária (datasource, `api.security.token.secret`,
`cors.allowed-origins`, config Flyway) precisa ser replicada no arquivo de teste. O arquivo
atual documenta isso no cabeçalho e ainda resolve um efeito colateral: os seeds (V13, V16,
V18, V20) ficam registrados no `flyway_schema_history` do banco de dev mas não existem no
classpath de teste — daí o `spring.flyway.ignore-migration-patterns=*:missing,*:future`.

### 3. As transações que sumiam do dashboard (INNER JOIN implícito)

O resumo do dashboard ignorava transações sem fatura. A query usava `t.invoice.dueDate`
direto no WHERE — e o Hibernate, ao navegar associação em path expression, gera **INNER JOIN
implícito**: toda transação com `invoice IS NULL` era filtrada silenciosamente. A correção
virou padrão da casa, visível em `TransactionRepository.sumByTenantAndTypeAndPeriod` e
`countByTenantAndPeriod`: `LEFT JOIN t.invoice inv` explícito, com o WHERE tratando os dois
ramos (`inv IS NOT NULL AND inv.dueDate BETWEEN ...` OR `inv IS NULL AND t.date BETWEEN ...`).
Qualquer query nova que filtre por campo de fatura deve seguir esse molde.

### 4. O principal detached (LazyInitializationException no usuário autenticado)

O `User` autenticado é carregado pelo `SecurityFilter` — um servlet filter que roda **antes**
do interceptor Open Session In View. Resultado: o principal circula pela aplicação como
entidade detached; qualquer acesso a associação LAZY dele explode com
`LazyInitializationException`. Duas regras saíram dessa batalha: (a) `findByEmail` faz
`JOIN FETCH u.tenant` (o tenant é necessário em praticamente todo request); (b) de qualquer
proxy LAZY pendurado no principal, só é seguro ler `.getId()` ou passar a referência como
parâmetro JPQL — nunca acessar outros campos.

### 5. 500 onde deveria ser 404 (a exceção de infra que vaza)

Services que lançavam `jakarta.persistence.EntityNotFoundException` (a da JPA) produziam 500
genérico, porque o `GlobalExceptionHandler` mapeia para 404 apenas a exceção de domínio
`com.fintech.api.exception.EntityNotFoundException`. A convenção: service **nunca** deixa
exceção de infra atravessar — sempre relança a do pacote `exception/`. O experimento da
tabela (grep pelo import da jakarta em `backend/src/main/java`, que deve dar vazio) é o
teste de regressão manual dessa regra.

### 6. O botão que não desabilitava (Zoneless × Reactive Forms)

Em projeto Zoneless, `[disabled]="form.invalid"` congela: `FormGroup` não é Signal e não há
zone.js para forçar re-render. A mesma família de bug: `computed()` lendo `control.value`
nunca recalcula. A ponte canônica é `toSignal(valueChanges/statusChanges, { initialValue })` —
aplicada de verdade em `transaction-form.ts`. Batalha irmã: effects que liam signals e
chamavam loaders que escreviam signals entravam em loop — resolvido com `untracked()` nos
handlers (`transaction-list.ts`). Bônus da mesma campanha: reset de flag `saving` sempre em
`finalize()` do observable, nunca no `next`, para não travar o formulário em erro HTTP.

### 7. O `auth.service.ts` zumbi (Orval)

Cada `npm run api:generate` ressuscita `frontend/src/app/core/api/auth/auth.service.ts`, que
conflita com a implementação própria de auth. Depois de o esquecimento quebrar o build
repetidas vezes, o passo "deletar o zumbi" foi automatizado como etapa 4/4 do
`./scripts/api-sync.sh`. Se o build quebrou em `core/api/auth/`, alguém regenerou por fora
do script. Da mesma família: schemas de resposta sem `required:` na spec fazem o Orval gerar
tudo opcional (forçando `!` no consumo), e o springdoc precisa ser exatamente `2.8.9` — o
2.6.0 é incompatível com Spring Boot 4.0.1 e derruba o backend.

### 8. A race da fatura lazy-create (ADR-001, issue #83)

Duas transações de cartão criadas em paralelo para o mesmo período disputavam o
`getOrCreate` da fatura; o segundo INSERT violava `UNIQUE(account_id, reference_year,
reference_month)` e — pior — contaminava a transação JPA externa. Solução em duas partes em
`InvoiceService`: `createNewInvoice` roda em `@Transactional(REQUIRES_NEW)` (via
self-injection por proxy, porque `@Transactional` não intercepta chamada interna), e o
`getOrCreate` captura o conflito de chave única e faz retry com `findBy`. A constraint única
é a rede de segurança; a transação separada é o que permite o retry limpo.

### 9. Os loops operacionais de agente (auditoria 2026-07-02)

Uma auditoria de sessões passadas encontrou os mesmos erros repetidos dezenas de vezes:
`npx vitest` cru em spec de componente (quebra fora do builder Angular), sessão bloqueada 7+
minutos esperando a suíte Maven no foreground, `backend/backend/...` por assumir que o cwd
persiste entre comandos Bash, e re-tentativas inúteis contra o MCP do SonarQube (que não tem
privilégio para quality gate). O resultado foi o trio de scripts anti-fricção
(`api-sync.sh`, `test-summary.sh`, `clean-worktrees.sh`) e as regras da tabela de gotchas
acima. Se você se pegar repetindo um comando que falhou do mesmo jeito, pare e procure o
sintoma aqui primeiro.

---

## Proveniência e manutenção

Fatos verificados contra o repo em **2026-07-04**. Para re-verificar:

```bash
# Migrations/seeds atuais (V21 era a última em 2026-07-04)
ls backend/src/main/resources/db/migration/ backend/src/main/resources/db/seed/

# Props de teste replicadas + ignore-migration-patterns
head -40 backend/src/test/resources/application-dev.properties

# Padrão LEFT JOIN explícito nas queries de período
grep -n "LEFT JOIN t.invoice" backend/src/main/java/com/fintech/api/repository/TransactionRepository.java

# Sentinelas de data
grep -n "LocalDate.of(1000\|LocalDate.of(9999" backend/src/main/java/com/fintech/api/service/TransactionService.java

# JOIN FETCH do tenant no principal
grep -n "JOIN FETCH" backend/src/main/java/com/fintech/api/repository/UserRepository.java

# Exceção de infra não deve aparecer (esperado: vazio)
grep -rn "jakarta.persistence.EntityNotFoundException" backend/src/main/java

# REQUIRES_NEW do lazy-create de fatura
grep -n "REQUIRES_NEW" backend/src/main/java/com/fintech/api/service/InvoiceService.java

# springdoc 2.8.9
grep -B2 "2.8.9" backend/pom.xml

# Remoção do auth.service.ts no pipeline
grep -n "auth.service" scripts/api-sync.sh

# Rate limiter (defaults 5 tentativas / 60 s)
grep -n "@Value" backend/src/main/java/com/fintech/api/config/LoginRateLimiter.java

# Padrões Zoneless em uso real
grep -n "toSignal" frontend/src/app/features/transaction/transaction-form/transaction-form.ts
grep -n "untracked(" frontend/src/app/features/transaction/transaction-list/transaction-list.ts
```
