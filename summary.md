# Fintech SaaS Multi-Tenant — Referência de Especificação

> Documento de referência técnica para Spec-Driven Development. Serve como fonte de verdade para domínio, contratos de API e regras de negócio implementadas.
> Última atualização: 2026-06-08

---

## Stack

| Camada | Tecnologia |
|--------|-----------|
| Backend | Java 21, Spring Boot 4.0.1, Spring Security, JPA/Hibernate |
| Frontend | Angular 21 Zoneless, Angular Material 3, Signals |
| Banco | PostgreSQL 16, Flyway migrations |
| Auth | JWT stateless |
| API Contract | OpenAPI 3 spec-first (`api-spec/openapi.yaml`) |
| Codegen Backend | `openapi-generator-maven-plugin` (`spring`, `interfaceOnly=true`) |
| Codegen Frontend | Orval CLI (`npm run api:generate`) |
| Testes Backend | JUnit 5, Mockito |
| Testes Frontend | Vitest |

---

## Modelo de Domínio

```
Tenant (UUID)
  ├── User (email, passwordHash, role: ADMIN | MEMBER)
  └── Account (name, type, color, icon, countInLiquidBalance, countInNetWorth, active)
       ├── CreditCardDetails (brand, lastFourDigits, limitAmount, closingDay, dueDay)
       │    └── [apenas para CREDIT_CARD]
       └── Invoice (referenceYear, referenceMonth, closingDate, dueDate, status)
            └── [criada lazily na primeira transação do período]

Tenant (UUID)
  └── Category (name, icon, color, parentId?, deletedAt?)
       └── Category (filhos — árvore multinível)

Tenant (UUID)
  └── InstallmentGroup (description, totalAmount, totalInstallments, account, category?)
       └── Transaction[] (N parcelas vinculadas)

Tenant (UUID)
  └── Transaction (description, amount, date, type, status)
       ├── FK → Account
       ├── FK → Category (nullable)
       ├── FK → InstallmentGroup (nullable)
       ├── FK → Invoice (nullable — somente CREDIT_CARD)
       └── transferId: UUID (nullable — par INCOME↔EXPENSE de transferências)

Tenant (UUID)
  └── Invitation (email, token, expiresAt)
```

---

## Migrations Flyway

| Versão | Schema |
|--------|--------|
| V1 | `tenants`, `users` |
| V2 | `credit_card_details` |
| V3 | `categories`, `transactions` |
| V4 | coluna `status` e campos de parcelamento legado em `transactions` |
| V5 | tabela `accounts` com `count_in_liquid_balance`, `count_in_net_worth` |
| V6 | tabela `invitations` |
| V7 | coluna `deleted_at TIMESTAMP` em `categories` |
| V8 | tabela `installment_groups` + FK nullable `installment_group_id` em `transactions` |
| V9 | tabela `invoices` + FK nullable `invoice_id` em `transactions` |

**Invariante:** migrations aplicadas são imutáveis. Correções sempre via nova versão.

---

## Enumerações

```
AccountType    : CHECKING | CREDIT_CARD | INVESTMENT | CASH
CardBrand      : VISA | MASTERCARD | ELO | AMEX | HIPERCARD
TransactionType   : INCOME | EXPENSE
TransactionStatus : PENDING | PAID | CANCELLED
InvoiceStatus  : OPEN | CLOSED | PAID
DeleteInstallmentScope : SINGLE | THIS_AND_NEXT | ALL
UserRole       : ADMIN | MEMBER
```

---

## Contratos de API

### Autenticação (`/auth`)

| Método | Rota | Auth | Descrição |
|--------|------|------|-----------|
| POST | `/auth/register` | público | Cria Tenant + User(ADMIN) + retorna JWT |
| POST | `/auth/login` | público | Valida credenciais + retorna JWT |
| POST | `/auth/accept-invite` | público | Valida token de convite + cria User(MEMBER) + retorna JWT |

**Regras:**
- Senha armazenada exclusivamente via BCrypt. Nunca retornada em DTO.
- JWT assina `sub = email`, validade configurável via `application.properties`.
- `SecurityFilter` valida JWT em toda requisição e popula `SecurityContextHolder`.

---

### Convites (`/invites`)

| Método | Rota | Auth | Descrição |
|--------|------|------|-----------|
| POST | `/invites` | ADMIN | Cria convite (email + token + expiresAt) |
| GET | `/invites/{token}` | público | Valida token + retorna { email, tenantName } |

---

### Contas (`/api/accounts`)

| Método | Rota | Descrição |
|--------|------|-----------|
| GET | `/api/accounts` | Lista contas ativas do tenant (ordenadas por nome) |
| POST | `/api/accounts` | Cria conta |
| GET | `/api/accounts/{id}` | Detalha conta (inclui `balance` calculado) |
| PUT | `/api/accounts/{id}` | Atualiza campos opcionais (PATCH semântico) |
| DELETE | `/api/accounts/{id}` | Arquiva conta (`active = false`) |

**Modelo Financeiro — a distinção entre liquidez e patrimônio**

Existe uma diferença fundamental entre "o que tenho disponível agora" e "o que tenho no total". Considere:

| Conta | Saldo | Disponível agora? | Patrimônio? |
|-------|-------|-------------------|-------------|
| Conta corrente Bradesco | R$ 3.000 | Sim | Sim |
| Carteira física | R$ 200 | Sim | Sim |
| CDB Nubank | R$ 15.000 | Não (requer resgate) | Sim |
| Cartão de crédito | R$ 1.200 em despesas | Não (compromisso futuro) | Não |

Soma ingênua: R$ 17.000. Resposta para "o que posso gastar agora?": R$ 3.200.

Os dois flags modelam exatamente essa distinção. O cartão de crédito tem `countInLiquidBalance = false` não porque suas transações sejam ignoradas — elas são registradas normalmente e impactam o Dashboard — mas porque o saldo do cartão representa uma despesa futura comprometida, não liquidez disponível. O dinheiro real só sai quando a fatura é paga via transferência da conta corrente, e essa transferência é que reduz o saldo líquido.

**Campos de classificação financeira:**

| Campo | Tipo | Pergunta que responde |
|-------|------|-----------------------|
| `countInLiquidBalance` | boolean | "Este dinheiro está disponível para uso imediato?" |
| `countInNetWorth` | boolean | "Este valor integra meu patrimônio total?" |

**Defaults por tipo de conta (aplicados pelo `AccountService` quando não informado):**

| Tipo | `countInLiquidBalance` | `countInNetWorth` | Raciocínio |
|------|----------------------|------------------|------------|
| `CHECKING` | `true` | `true` | Dinheiro acessível imediatamente |
| `CASH` | `true` | `true` | Dinheiro na mão |
| `INVESTMENT` | `false` | `true` | É patrimônio, mas requer resgate para virar liquidez |
| `CREDIT_CARD` | `false` | `true` | Linha de crédito — passivo, não ativo disponível |

Um investimento tem `countInLiquidBalance = false` e `countInNetWorth = true`: é riqueza, mas não liquidez imediata. O usuário pode setar `countInNetWorth = false` para contas que não representam patrimônio próprio (ex: conta de terceiros gerenciada transitoriamente).

**Onde esses flags são usados:**
- `countInLiquidBalance` → `TransactionRepository.sumNetLiquidBalanceByTenant()` → campo `totalAccountBalance` do Dashboard (saldo acumulado sem filtro de período).
- `countInNetWorth` → armazenado, **ainda não consumido por nenhuma query**. Reservado para futura tela de "Patrimônio Total".

**Frontend:** ao alterar o tipo da conta, `countInLiquidBalance` é ajustado automaticamente via `effect()` (`CHECKING` | `CASH` → `true`; demais → `false`). O usuário pode sobrescrever. O `{ emitEvent: false }` no `patchValue` suprime eventos reativos em cascata — o ajuste é de default, não uma ação do usuário.

**`balance` na resposta:** calculado em tempo real via `AccountRepository.calculateBalance()`:
```sql
SUM(CASE WHEN type=INCOME THEN amount ELSE -amount END)
WHERE account = :account AND status <> CANCELLED
```

---

### Categorias (`/api/categories`)

| Método | Rota | Descrição |
|--------|------|-----------|
| GET | `/api/categories?includeArchived=false` | Árvore de categorias (raízes + filhos aninhados) |
| POST | `/api/categories` | Cria categoria (com `parentId` opcional) |
| GET | `/api/categories/{id}` | Detalha categoria |
| PUT | `/api/categories/{id}` | Atualiza; propaga `icon`/`color` para descendentes |
| DELETE | `/api/categories/{id}` | Soft delete em cascata OU 409 se subárvore tem transações |
| POST | `/api/categories/{id}/archive?targetCategoryId={uuid}` | Soft delete + reassociação opcional de transações |

**Regras:**
- Soft delete: `deleted_at TIMESTAMP` em cascata em toda a subárvore.
- `DELETE` retorna `409 { transactionCount }` se qualquer nó da subárvore tem transações vinculadas. Frontend exibe `CategoryArchiveDialog`.
- `archive` com `targetCategoryId`: reassocia todas as transações da subárvore para a categoria alvo antes do soft delete.
- Herança: filho criado sem `icon`/`color` herda do pai. `PUT` no pai propaga para descendentes via `propagateToDescendants()`.
- Validação anti-circular: não é possível definir um descendente como pai.
- `archived: boolean` em `CategoryResponseDTO` — `deletedAt != null`.
- `TransactionResponseDTO.categoryArchived: boolean` — transações com categoria arquivada exibem nome taxado com tooltip.
- `TransactionResponseDTO.categoryPath: string` — path completo da categoria (ex: `"Pets → Ração"`), construído percorrendo a cadeia `parent` dentro de `@Transactional`. Usado no breakdown de faturas para agrupar subcategorias com contexto hierárquico.
- `TransactionResponseDTO.categoryIcon: string` — ícone da categoria folha. Exibido no breakdown de faturas ao lado do path.

---

### Transações (`/api/transactions`)

| Método | Rota | Descrição |
|--------|------|-----------|
| GET | `/api/transactions` | Lista transações do tenant (query params opcionais abaixo) |
| GET | `/api/transactions/{id}` | Detalha transação |
| POST | `/api/transactions` | Cria 1..N transações (parcelamento gera N) |
| PUT | `/api/transactions/{id}` | Atualiza (com propagação opcional para parcelas futuras) |
| DELETE | `/api/transactions/{id}?scope=SINGLE\|THIS_AND_NEXT\|ALL` | Exclui por escopo |

**Query params de filtro (todos opcionais, combinam livremente):**

| Param | Tipo | Descrição |
|-------|------|-----------|
| `invoiceId` | UUID | Filtra por fatura específica |
| `accountId` | UUID | Filtra por conta |
| `status` | `TransactionStatus` | Filtra por status (`PENDING`, `PAID`, `CANCELLED`) |
| `type` | `TransactionType` | Filtra por tipo (`INCOME`, `EXPENSE`) |
| `startDate` | `YYYY-MM-DD` | Início do intervalo de datas (obrigatório com `endDate`) |
| `endDate` | `YYYY-MM-DD` | Fim do intervalo de datas (obrigatório com `startDate`) |

**Regra de data para filtro por período:**
- Parcela de cartão (`installmentGroup IS NOT NULL AND inv IS NOT NULL`) → filtra por `inv.dueDate`
- Demais (incluindo avulsa de cartão) → filtra por `t.date`
- `startDate` e `endDate` devem ser informados juntos; parcial → `400 Bad Request`

**Regra de ordenação (`effectiveSortDate`):**
- Parcela de cartão (`installmentGroup != null` e `invoice != null`) → `invoice.dueDate`
- Demais → `transaction.date`
- Sort descendente (mais recente primeiro). Implementado em memória (JPA não computa `dueDate` de fatura em ORDER BY).

**Regra de exibição da coluna "Data" no frontend:**
- Parcela de cartão → exibe `invoice.dueDate` (distribui parcelas pelo mês de impacto no orçamento)
- Transação avulsa (qualquer tipo, incluindo avulsa de cartão) → exibe `transaction.date`

**Criação parcelada:**
```
POST /api/transactions { totalInstallments: N, amount, date, accountId: [CREDIT_CARD] }

Para i = 0..N-1:
  invoiceMonth = resolveInvoiceMonth(date, closingDay).plusMonths(i)
  invoice = InvoiceService.getOrCreate(account, year, month)
  Transaction {
    date = purchaseDate,          ← sempre a data da compra
    installmentNumber = i + 1,
    amount = totalAmount / N,
    invoice = faturaDoMês(i)
  }
```

**`resolveInvoiceMonth(purchaseDate, closingDay)`:**
- `purchaseDate.day <= closingDay` → fatura do mês corrente
- `purchaseDate.day > closingDay` → fatura do mês seguinte

**Exclusão por escopo (`DELETE ?scope=`):**
| Escopo | Comportamento |
|--------|--------------|
| `SINGLE` | Remove apenas esta transação |
| `THIS_AND_NEXT` | Remove esta e as próximas com `status=PENDING` (protege `PAID`) |
| `ALL` | Remove todas as `PENDING` do grupo (protege `PAID`) |
Retorna `{ deleted: int, skippedPaid: int }`.

**Propagação na edição (`PUT` com `propagate: string[]`):**
- Aplica os campos listados às parcelas futuras com `installmentNumber > atual` e `status=PENDING`.
- `PAID` nunca é revertido para `PENDING` via propagação.

---

### Transferências (`/api/transfers`)

| Método | Rota | Descrição |
|--------|------|-----------|
| POST | `/api/transfers` | Cria par EXPENSE (origem) + INCOME (destino) |
| DELETE | `/api/transfers/{transferId}` | Remove os dois lançamentos pelo `transferId` compartilhado |

**Modelo double-entry:** cada transferência gera dois `Transaction` com o mesmo `transferId` UUID. Não existe entidade `Transfer` — são dois lançamentos simétricos.

---

### Faturas (`/api/invoices`)

| Método | Rota | Body | Descrição |
|--------|------|------|-----------|
| GET | `/api/invoices?accountId={uuid}` | — | Lista faturas de uma conta CREDIT_CARD |
| GET | `/api/invoices/{id}` | — | Detalha fatura (inclui `totalAmount`, `transactionCount`) |
| POST | `/api/invoices/{id}/close` | — | `OPEN → CLOSED` — marcador administrativo, sem side effects |
| POST | `/api/invoices/{id}/pay` | `{ sourceAccountId }` | `CLOSED → PAID` — ver ciclo de pagamento abaixo |

**Ciclo de vida completo:**
```
OPEN → [fechar] → CLOSED   (novas transações ainda aceitas; frontend exibe aviso)
CLOSED → [pagar] → PAID    (ponto de não-retorno)
```

**Fechar (`OPEN → CLOSED`):** apenas muda o status. Nenhuma transação é afetada. Novas transações do período ainda podem ser associadas à fatura fechada — útil para cobranças atrasadas.

**Pagar (`CLOSED → PAID`) — dentro de uma única `@Transactional`:**
1. Todas as transações `PENDING` da fatura → `PAID` via `@Modifying` batch (sem N+1).
2. Se `total > 0`: cria `EXPENSE` na conta de origem (`sourceAccountId`) com:
   - `amount = soma das transações não canceladas`
   - `date = LocalDate.now()` ← data real do pagamento, não o `dueDate`
   - `description = "Pagamento fatura {accountName} {MM}/{yyyy}"`
   - `invoice = null`, `installmentGroup = null`
3. Status da fatura → `PAID`.

**Por que criar a EXPENSE?** `CREDIT_CARD` tem `countInLiquidBalance = false`. Sem o débito, os gastos do cartão nunca impactam o saldo líquido. O débito fecha o ciclo: compra (sem impacto imediato) → pagamento da fatura (saída de caixa real).

**Validações em `pay`:**
- `sourceAccountId` deve pertencer ao tenant → 404
- Tipo da conta de origem `≠ CREDIT_CARD` → 422
- Status da fatura deve ser `CLOSED` → 422

**Criação lazy (`InvoiceService.getOrCreate`):**
- Chamada automaticamente ao criar transações de cartão.
- Nunca criada manualmente pelo frontend.
- `UNIQUE(account_id, reference_year, reference_month)` garante idempotência.

**Cálculo de `dueDate`:**
- `dueDay >= closingDay` → vencimento no mesmo mês da referência
- `dueDay < closingDay` → vencimento no mês seguinte

---

### Grupos de Parcelamento (`/api/installment-groups`)

| Método | Rota | Descrição |
|--------|------|-----------|
| GET | `/api/installment-groups` | Lista grupos do tenant |
| GET | `/api/installment-groups/{id}` | Detalha grupo (metadados + parcelas) |
| DELETE | `/api/installment-groups/{id}` | Remove parcelas PENDING do grupo inteiro |
| PATCH | `/api/installment-groups/{id}` | Atualiza metadados do grupo |

---

### Dashboard (`/api/dashboard`)

| Método | Rota | Descrição |
|--------|------|-----------|
| GET | `/api/dashboard/summary?period=YYYY-MM` | Resumo financeiro do período |

**Resposta `DashboardSummaryDTO`:**
```
{
  period,
  income,           ← soma de INCOME excluindo CANCELLED
  expense,          ← soma de EXPENSE excluindo CANCELLED
  balance,          ← income - expense
  transactionCount, ← count excluindo CANCELLED
  totalAccountBalance ← posição líquida atual (sem filtro de período)
}
```

**Regra de período (LEFT JOIN obrigatório):**
```sql
LEFT JOIN t.invoice inv
WHERE (inv IS NOT NULL AND inv.dueDate BETWEEN :start AND :end)
   OR (inv IS NULL    AND t.date       BETWEEN :start AND :end)
AND t.status <> CANCELLED
```
O `LEFT JOIN` explícito é obrigatório. `t.invoice.dueDate` em WHERE gera INNER JOIN implícito no Hibernate, excluindo transações sem fatura (CHECKING, CASH, INVESTMENT).

**`totalAccountBalance` — posição líquida acumulada:**
```sql
SUM(CASE WHEN type=INCOME THEN amount ELSE -amount END)
WHERE status <> CANCELLED
  AND account.countInLiquidBalance = true
-- Sem filtro de período — representa saldo acumulado desde sempre
```

---

## Segurança

```
Rotas públicas:
  POST /auth/login, /auth/register, /auth/accept-invite
  GET  /invites/{token}
  GET  /openapi.yaml, /swagger-ui/**, /actuator/health

hasRole(ADMIN):
  POST /invites

anyRequest → authenticated (JWT válido obrigatório)
```

**Invariante de tenant:** toda query de dados de negócio filtra por `tenant` do usuário autenticado. Vazamento de tenant é o bug mais grave possível.

---

## Frontend — Padrões Implementados

### Zoneless + Signals
- `provideZonelessChangeDetection()` — sem zone.js.
- Estado local: `signal()`, `computed()`, `effect()`.
- Bridge com `FormControl`: `toSignal(control.valueChanges, { initialValue: ... })` — `computed()` não reage a `FormControl.value` diretamente.
- RxJS apenas para HTTP (`HttpClient`) e streams assíncronos.

### Formulário de Transação (transaction-form)
Ordem dos campos após feat #39:
1. **Conta** (determina campos disponíveis)
2. Tipo (INCOME/EXPENSE)
3. Status
4. Descrição, Valor, Data, Categoria
5. [Se `isCreditCard()`] Toggle parcelamento → campos aparecem
6. [Se `isInstallment`] Preview live de parcelas (`installment-preview.ts`)

Comportamento reativo:
- `isCreditCard = computed(() => selectedAccount?.type === 'CREDIT_CARD')`
- `effect()` desativa `isInstallment` ao trocar para conta não-cartão
- `installmentPreview = computed(() => buildInstallmentPreview(amount, N, date, valueMode, creditCard))`

### Listagem de Transações com Filtros (issue #41)

**Componente de filtros (`TransactionFiltersComponent`):**
- `accounts = input<AccountResponse[]>([])` + `filterChange = output<TransactionFilters>()`
- Signals internos para cada campo; nenhum emit no init — `TransactionList` faz a carga inicial
- Seletor de mês integrado: ◀/▶ preenche `startDate`/`endDate`; edição manual → `resolveMonthKey()` retorna `'custom'` → label "Personalizado"

**`TransactionList` — padrão de reatividade:**
```ts
filters = signal<TransactionFilters>(DEFAULT_FILTERS);

onFilterChange(newFilters: TransactionFilters): void {
  this.filters.set(newFilters);
  untracked(() => this.loadTransactions(newFilters)); // evita rastrear signals lidos dentro de loadTransactions
}
```
`untracked()` é necessário porque `loadTransactions` pode ler signals internamente; sem ele, qualquer mudança de signal dentro da função dispararia o efeito novamente em loop.

**Agrupamento por período — single `mat-table` com múltiplos `*matRowDef`:**
- `buildDisplayRows(txs, expandedIds, groupByPeriod)` insere linhas `period-header` entre grupos
- `isPeriodHeader = (_, row) => row.kind === 'period-header'`
- `<tr mat-row *matRowDef="...; when: isPeriodHeader">` — Angular avalia `when` em ordem; primeiro `true` vence
- `period-header` usa `[attr.colspan]="displayedColumns.length"` para ocupar toda a largura sem duplicar colunas

**Utilitários puros (`transaction-list.utils.ts`):**
- `effectiveMonth(t)`: `installmentGroupId && invoiceDueDate` → mês do `invoiceDueDate`; demais → mês do `date`
- `groupByEffectiveMonth(txs)`: single-pass reduce para `totalIncome`/`totalExpense`/`balance`; sort descrescente
- `resolveMonthKey(start, end)`: `'YYYY-MM'` se range é exatamente um mês, `'custom'` se parcial, `''` se null
- `monthBounds(key)`: primeiro e último dia do mês (usa `new Date(y, m, 0)` para último dia correto em qualquer mês)

### Testes — estratégia de isolamento
Funções de lógica pura em arquivos sem imports Angular (ex: `transaction-list.utils.ts`, `installment-preview.ts`) para compatibilidade com Vitest sem `TestBed`.

---

## Arquitetura Backend

**Camadas:** `Controller → Service → Repository`. Entidade JPA nunca exposta em controller. DTO em todas as bordas.

**Pacote por domínio (híbrido layer/feature):**
```
domain/
  account/, category/, installment/, invitation/, invoice/,
  tenant/, transaction/, user/, enums/
dto/        → account/, category/, dashboard/, installment/, invoice/, transaction/, transfer/
controller/ → um por domínio
service/    → um por domínio
repository/ → um por entidade
config/     → SecurityConfigurations, OpenApiConfig
exception/  → GlobalExceptionHandler, EntityNotFoundException
```

**Anti-pattern evitado:** `services` nunca lançam exceções de infra (`jakarta.persistence.EntityNotFoundException`). Sempre relançar via `com.fintech.api.exception.EntityNotFoundException` para o `GlobalExceptionHandler` capturar corretamente.

---

## Logging Estruturado

### MDC (Mapped Diagnostic Context)

Cada requisição carrega contexto rastreável via `RequestIdFilter` e `SecurityFilter`:

| Chave MDC   | Quando populado | Valor |
|-------------|-----------------|-------|
| `requestId` | Toda requisição | UUID gerado em `RequestIdFilter`; devolvido como header `X-Request-ID` |
| `userId`    | Após autenticação JWT | UUID do usuário autenticado |
| `tenantId`  | Após autenticação JWT | UUID do tenant |

**Padrão dev** (console legível):
```
%d{HH:mm:ss.SSS} %-5level [%X{requestId:--}][%X{userId:--}] %logger{36} - %msg%n
```

**Padrão prod** (JSON estruturado, `application-prod.properties`):
```properties
logging.structured.format.console=logstash
```
JSON nativo do Spring Boot 4.0 — campos MDC incluídos automaticamente, parseable por qualquer agregador (ELK, Loki, CloudWatch).

### Onde logar — regra por camada

| Camada | O que logar |
|--------|-------------|
| `RequestIdFilter` | Nenhum log — só popula MDC |
| `SecurityFilter` | `WARN` em token inválido ou usuário não encontrado |
| `GlobalExceptionHandler` | `ERROR` com stack trace apenas para 5xx |
| `Service` | `INFO` em transições de estado de negócio relevantes (ex: fatura fechada/paga) |
| `Controller` | Nunca — o MDC já rastreia a requisição |

**Regra:** não logar dados sensíveis (senhas, tokens JWT, CPF). Nunca logar em `DEBUG` em produção por padrão.

### Adicionar log em novos Services

```java
private static final Logger log = LoggerFactory.getLogger(MinhaClasse.class);

// Transição de estado ou evento de negócio relevante:
log.info("invoice.closed invoiceId={} accountId={}", invoice.getId(), invoice.getAccount().getId());

// Erro inesperado (normalmente já tratado pelo GlobalExceptionHandler):
log.error("Erro ao processar X para tenantId={}", tenantId, e);
```

O `tenantId` e `userId` já estão no MDC — não é necessário repetir em todo log. Adicionar apenas quando ajuda a filtrar no contexto específico do método.

---

## Fluxo OpenAPI (Spec-First)

```
1. Editar api-spec/openapi.yaml
2. cd backend && ./mvnw generate-sources
   → interfaces Spring em target/ (não commitadas)
3. cd frontend && npm run api:generate
   → services em frontend/src/app/core/api/ (commitados)
4. cp api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml
5. Erros de compilação Java/TypeScript = feedback de divergência de contrato
```

**Armadilhas conhecidas:**
- `auth/auth.service.ts` é regenerado pelo Orval a cada `npm run api:generate` — deletar manualmente antes de usar.
- Sem `required: [...]` em schemas de resposta → Orval gera campos opcionais → `!` assertions nos templates Angular.
- `springdoc` na versão correta: `2.8.9` (incompatível com `2.6.0` no Spring Boot 4.0.1).

---

## Estado dos Testes (2026-06-05)

- Backend: 59 testes, 0 falhas
- Frontend TypeScript: 0 erros de tipo (build limpo)
- Frontend Vitest: 7 testes de lógica pura passando; 34 falhas de configuração do TestBed (problema de setup do Vitest com Angular, não de código de produção)

---

## Próximos Passos

- Filtros na listagem de transações (período, tipo, status, conta)
- Gráficos no dashboard (evolução mensal, breakdown por categoria/conta)
- Tela de Patrimônio Total consumindo `countInNetWorth`
