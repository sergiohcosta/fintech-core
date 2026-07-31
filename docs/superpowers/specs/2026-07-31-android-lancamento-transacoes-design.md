# Spec: App Android — Lançamento de Transações

**Data:** 2026-07-31
**Status:** proposto (aguardando aprovação)
**Issue:** nenhuma ainda (frente nova, sem issue no backlog atual)
Stack: @tech.md · Domínio: @domain.md · Contratos: `api-spec/openapi.yaml`

## 1. Contexto e escopo

O fintech-core hoje tem dois clientes do backend: o frontend web Angular e nenhum cliente
mobile. Este é o primeiro app Android do projeto — um **cliente adicional da mesma API**
(`api-spec/openapi.yaml`), não um sistema separado. Mesmo tenant, mesmo login, mesmas regras
de negócio (que continuam vivendo só no backend).

**Objetivo:** permitir lançar uma transação (incluindo parcelada em cartão) pelo celular,
mesmo sem conexão no momento do lançamento (ex: no caixa do mercado), e ver as últimas
transações lançadas.

**Escopo desta versão:**
- Login (mesmas credenciais do web) com sessão JWT persistida.
- Criar transação: descrição, valor, data, tipo, conta, categoria, parcelamento (se conta
  for cartão de crédito).
- Fila local (outbox) para lançar sem conexão, sincronizada automaticamente quando a
  conexão volta.
- Lista de leitura das últimas transações do tenant.

**Fora de escopo desta versão** (§7 detalha): editar/excluir transação, telas de
conta/categoria/fatura/orçamento/recorrência, notificações push, biometria, dark mode,
múltiplos tenants no mesmo dispositivo, testes instrumentados de UI.

## 2. Decisões arquiteturais

| # | Decisão | Escolha | Alternativa descartada |
|---|---|---|---|
| a | Stack de UI | Kotlin + Jetpack Compose | XML Views (legado — Google já recomenda Compose para projetos novos) |
| b | Arquitetura de app | MVVM + Repository, camadas `ui/ → data/repository/ → data/{remote,local}/`, Hilt para DI | MVI (mais cerimônia que o escopo justifica); sem Repository (acopla ViewModel direto a Retrofit/Room, dificulta a decisão online-vs-fila) |
| c | Cliente HTTP | Codegen a partir de `api-spec/openapi.yaml` via `openapi-generator` (gerador `kotlin`, biblioteca `jvm-retrofit2`), rodando no build Gradle — mesmo espírito do plugin Maven do backend e do Orval no frontend web: **um único** contrato-fonte para os 3 clientes | DTOs Kotlin escritos à mão — reintroduz o mesmo risco que o projeto já eliminou no backend/frontend: contrato divergindo silenciosamente do `openapi.yaml` conforme a API evolve |
| d | Sincronização offline | Outbox local (Room) + `WorkManager` drenando a fila — só cobre `create`, sem sync bidirecional | Sync completo com reconciliação de conflitos — desnecessário: o app não edita/exclui, então não há conflito a resolver; histórico sempre busca fresco da API quando online, sem espelho local |
| e | Sessão | JWT em `DataStore` com `EncryptedFile` (Jetpack Security) — mesmo token do web, sem refresh token | Refresh token — o backend não tem esse fluxo hoje (só JWT direto); implementar exigiria mudança no backend, fora do escopo de um cliente novo |
| f | Ambiente de API | `localhost:8080` via emulador (`10.0.2.2:8080`) nesta v1 — sem build variants dev/prod ainda | Build flavors dev/prod (como o Angular tem `environments/`) — adiado para quando o app for além do ambiente de desenvolvimento local |

**(c) Codegen em vez de DTOs manuais.**
O projeto já resolveu esse problema duas vezes — `openapi-generator-maven-plugin` no backend
(interfaces Spring a partir do spec) e Orval no frontend (`npm run api:generate`) — e as duas
vezes a razão foi a mesma: escrita manual funciona no dia 1 e diverge silenciosamente no dia
30, quando um campo muda no `openapi.yaml` e ninguém lembra de atualizar o cliente por igual.
Um terceiro cliente reescrevendo DTOs à mão reintroduziria exatamente esse risco. O plugin
`openapi-generator` tem gerador `kotlin` com biblioteca `jvm-retrofit2` nativa — gera
data classes + interface Retrofit a partir do mesmo `api-spec/openapi.yaml`, plugado numa
task Gradle (`generateAndroidApi`, análoga ao `generate-sources` do Maven), com saída em
`build/generated/` (não versionada, mesmo padrão do backend). Isso também implica adotar o
script `api-sync.sh` como o ponto de regeneração dos 3 clientes quando o contrato mudar —
tratado no plano de implementação, não nesta spec.

**(d) Por que outbox sem reconciliação é suficiente.**
`TransactionRequestDTO` já é auto-contido (`description`, `amount`, `date`, `type`,
`accountId`, `categoryId?`, `totalInstallments?`) — o backend faz todo o trabalho de split de
parcelas a partir de uma única chamada (`resolveInvoiceMonth`/parcela por parcela, ver
`summary.md`). O app não replica nenhuma lógica de negócio, só serializa o request e tenta
enviá-lo — hoje ou mais tarde. Sem tela de edição, um item da fila só tem dois destinos:
confirmado (removido da fila) ou descartado pelo usuário (removido sem nunca ter sido
enviado). Isso elimina a classe inteira de problema "o que fazer quando o servidor e o
cliente divergem sobre o mesmo registro".

## 3. Arquitetura

```
ui/          → Compose screens + ViewModels (MVVM)
data/
  remote/    → Retrofit (AuthApi, AccountApi, CategoryApi, TransactionApi)
  local/     → Room (PendingTransactionDao — só o outbox, não um cache do domínio)
  repository/→ AuthRepository, AccountRepository, CategoryRepository, TransactionRepository
di/          → módulos Hilt (Retrofit/OkHttp, Room, DataStore)
sync/        → SyncWorker (WorkManager)
```

- ViewModel expõe `StateFlow` — a UI só observa.
- `TransactionRepository` decide online-vs-fila; ViewModel e UI não sabem se um lançamento
  foi direto pra API ou caiu na fila (só recebem "salvo" ou "erro de validação").
- Interceptor OkHttp injeta o `Authorization: Bearer <jwt>` lido do `DataStore`; resposta 401
  dispara limpeza de sessão + navegação para Login (mesma regra do `AuthGuard` web).

## 4. Telas e fluxo de navegação

```
Login ──(sucesso)──▶ TransactionList ──(FAB "+")──▶ NewTransaction ──(salvar)──▶ volta pra List
   ▲                                                                                  │
   └──────────────────────(JWT expirado / logout)───────────────────────────────────┘
```

- **Login** — email/senha → `POST /auth/login`. Erro genérico (sem enumeração de usuário,
  mesma UX do web); trata 429 (rate limit) com mensagem própria.
- **TransactionList** — `GET /api/transactions` (últimas N), pull-to-refresh. Itens ainda na
  fila local aparecem com um indicador visual de "pendente de envio", distinto dos já
  confirmados pela API.
- **NewTransaction** — descrição, valor (parser pt-BR e ponto-decimal, mesmo espírito do
  `transaction-form.utils.ts` do web), data, tipo (INCOME/EXPENSE), conta (`GET
  /api/accounts`), categoria (`GET /api/categories`, árvore simplificada, sem CRUD). Campo
  "número de parcelas" só aparece quando a conta selecionada tem `type == CREDIT_CARD`.
  Envio: uma única chamada `POST /api/transactions` com `totalInstallments` preenchido — o
  backend faz o resto.

Sem edição/exclusão de transação nesta versão.

## 5. Modelo de dados local e sincronização

```sql
-- Room, tabela única
PendingTransaction(
  localId: Long PK autogen,
  payloadJson: String,      -- TransactionRequestDTO serializado (Moshi/kotlinx.serialization)
  createdAt: Instant,
  status: ENUM(PENDING, FAILED),
  errorMessage: String?     -- só quando o backend rejeitou (400)
)
```

**Ao salvar um lançamento:**
1. `TransactionRepository.create(dto)` tenta `POST /api/transactions` direto.
2. Sucesso → confirmado, aparece na lista no próximo refresh.
3. Falha de **rede** (sem conexão, timeout) → grava `PendingTransaction(status=PENDING)`; UI
   confirma "salvo, será enviado quando houver conexão".
4. Falha de **validação** do backend (400) → **não** entra na fila (reenviar não corrige
   sozinho); erro mostrado na hora, formulário mantém os dados para correção.

**`SyncWorker` (`CoroutineWorker`, WorkManager):**
- Gatilhos: `Constraints.NETWORK_CONNECTED` (dispara ao reconectar) + `PeriodicWorkRequest` a
  cada 15 min como backstop + tentativa manual no início da sessão do app.
- Drena itens `PENDING` em ordem de criação, um por vez: sucesso remove a linha; falha de
  rede aborta o ciclo (tenta de novo no próximo gatilho, sem perder a ordem); falha de
  validação (400) marca `FAILED` com `errorMessage` e **não** bloqueia os itens seguintes da
  fila.
- Itens `FAILED` aparecem na lista com indicador de erro; usuário só pode descartar
  (`DELETE` local, sem chamada à API) — sem edição inline do item da fila nesta versão.

## 6. Tratamento de erro

| Situação | Comportamento |
|---|---|
| 401 (JWT expirado/inválido) | Limpa sessão local, navega para Login |
| 429 no login (rate limit) | Mensagem "muitas tentativas, aguarde" |
| Falha de rede em leitura (lista, dropdowns) | Estado de erro na tela com "tentar novamente"; nunca crash |
| 400 no formulário | Mapeia a mensagem do backend para o campo relevante |

## 7. Fora de escopo (próximas fatias, se o app crescer)

- Editar/excluir transação pelo app.
- Telas de conta, categoria, fatura, orçamento, recorrência.
- Build variants dev/prod (aponta só para `localhost:8080` nesta v1).
- Refresh token (depende de mudança no backend).
- Biometria, notificações push, dark mode, multi-tenant no mesmo device.
- Testes instrumentados de UI (Compose UI test) — só unit tests JVM nesta versão.

## 8. Testes

- Lógica pura sem Android (parser de valor, decisão "vai pra fila ou não", mapeamento de
  erro) em Kotlin puro — testável com JUnit sem instrumentação, mesmo princípio dos arquivos
  `*-utils.ts` do frontend web.
- `Repository` e `SyncWorker` com unit tests JVM (MockWebServer para o Retrofit, Room
  in-memory para o Dao).
- Sem teste instrumentado de UI nesta versão — validação de tela é manual.
