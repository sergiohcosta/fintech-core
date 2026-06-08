# Transaction Filters & Grouping — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adicionar filtros (conta, status, tipo, período) e agrupamento por período à listagem de transações, com filtragem no backend via query params.

**Architecture:** Os 5 novos query params opcionais são adicionados à OpenAPI spec e propagados até o repositório via JPQL com `(:param IS NULL OR condição)`. O agrupamento é puramente de apresentação no frontend, implementado via uma nova row kind `period-header` no `buildDisplayRows`, eliminando a necessidade de um segundo `mat-table` no HTML.

**Tech Stack:** Java 21 / Spring Boot 4 / Spring Data JPA / JPQL; Angular 21 Zoneless / Signals / Angular Material 3 / Orval codegen / Vitest.

**Spec:** `docs/superpowers/specs/2026-06-08-transaction-filters-design.md`

---

## Mapa de arquivos

| Arquivo | Operação |
|---|---|
| `api-spec/openapi.yaml` | Adicionar 5 query params ao `listTransactions` |
| `backend/.../repository/TransactionRepository.java` | Novo método `findAllByTenantWithFilters` |
| `backend/.../service/TransactionService.java` | Atualizar assinatura de `findAll()` |
| `backend/.../controller/TransactionController.java` | Novos `@RequestParam`, implementar interface regenerada |
| `backend/src/test/.../controller/TransactionControllerTest.java` | Atualizar mock + novos testes de filtro |
| `frontend/src/app/core/api/transactions/transactions.service.ts` | Auto-regenerado pelo Orval |
| `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts` | Adicionar `PeriodGroup`, `effectiveMonth`, `groupByEffectiveMonth`, `resolveMonthKey`, `monthBounds`, `formatMonthLabel`; refatorar `buildDisplayRows` para suportar agrupamento |
| `frontend/src/app/features/transaction/transaction-list/transaction-list.spec.ts` | Testes para as novas funções de utils |
| `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.types.ts` | Criar: `TransactionFilters`, `DEFAULT_FILTERS` |
| `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.ts` | Criar: componente standalone com signals internos |
| `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.html` | Criar: template do painel de filtros |
| `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.scss` | Criar: estilos do painel de filtros |
| `frontend/src/app/features/transaction/transaction-list/transaction-list.ts` | Integrar filtros, chips, accounts, `filterEffect`, `groupedRows` |
| `frontend/src/app/features/transaction/transaction-list/transaction-list.html` | Botão "Filtrar" + painel colapsável + chips + row `period-header` |
| `frontend/src/app/features/transaction/transaction-list/transaction-list.scss` | Estilos para filtros, chips e period-header |

---

## Task 1: OpenAPI spec — adicionar 5 query params + regenerar código

**Files:**
- Modify: `api-spec/openapi.yaml` (linhas ~791-797)
- Auto-regenerado: `backend/src/main/java/com/fintech/api/openapi/TransactionsApi.java`
- Auto-regenerado: `frontend/src/app/core/api/transactions/transactions.service.ts`

- [ ] **Step 1.1: Adicionar os 5 params ao `listTransactions` na spec**

No arquivo `api-spec/openapi.yaml`, localizar a seção `listTransactions` (operationId na linha ~790). Substituir o bloco `parameters` existente pelo bloco abaixo. O `invoiceId` já existe — apenas acrescentar os novos após ele:

```yaml
      parameters:
        - name: invoiceId
          in: query
          required: false
          schema:
            type: string
            format: uuid
        - name: accountId
          in: query
          required: false
          schema:
            type: string
            format: uuid
        - name: status
          in: query
          required: false
          schema:
            $ref: '#/components/schemas/TransactionStatus'
        - name: type
          in: query
          required: false
          schema:
            $ref: '#/components/schemas/TransactionType'
        - name: startDate
          in: query
          required: false
          schema:
            type: string
            format: date
        - name: endDate
          in: query
          required: false
          schema:
            type: string
            format: date
```

- [ ] **Step 1.2: Regenerar a interface backend**

```bash
cd backend && ./mvnw compile
```

Resultado esperado: `BUILD SUCCESS`. O arquivo `TransactionsApi.java` gerado agora terá o método `listTransactions` com os 6 parâmetros. Se houver `BUILD FAILURE`, o `TransactionController` precisa implementar a nova assinatura antes que o projeto compile — nesse caso, avance para o Task 2 primeiro e volte ao step 1.3.

- [ ] **Step 1.3: Regenerar o serviço frontend via Orval**

```bash
cd frontend && npm run api:generate
```

Resultado esperado: `frontend/src/app/core/api/fintechSaaSAPI.schemas.ts` passa a ter:

```ts
export type ListTransactionsParams = {
  invoiceId?: string;
  accountId?: string;
  status?: TransactionStatus;
  type?: TransactionType;
  startDate?: string;
  endDate?: string;
};
```

- [ ] **Step 1.4: Commit**

```bash
git add api-spec/openapi.yaml
git commit -m "feat(api-spec): adiciona filtros por conta, status, tipo e período ao listTransactions"
```

---

## Task 2: Backend — repository, service, controller + testes (TDD)

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/repository/TransactionRepository.java`
- Modify: `backend/src/main/java/com/fintech/api/service/TransactionService.java`
- Modify: `backend/src/main/java/com/fintech/api/controller/TransactionController.java`
- Modify: `backend/src/test/java/com/fintech/api/controller/TransactionControllerTest.java`

- [ ] **Step 2.1: Adicionar `findAllByTenantWithFilters` ao `TransactionRepository`**

No arquivo `TransactionRepository.java`, adicionar após o método `findAllByTenantWithDetails`:

```java
@Query("""
  SELECT t FROM Transaction t
  LEFT JOIN FETCH t.installmentGroup
  LEFT JOIN FETCH t.category
  LEFT JOIN FETCH t.account
  LEFT JOIN FETCH t.invoice inv
  WHERE t.tenant = :tenant
    AND (:accountId IS NULL OR t.account.id = :accountId)
    AND (:status    IS NULL OR t.status = :status)
    AND (:type      IS NULL OR t.type = :type)
    AND (
      :startDate IS NULL OR :endDate IS NULL
      OR (t.installmentGroup IS NOT NULL AND inv IS NOT NULL AND inv.dueDate BETWEEN :startDate AND :endDate)
      OR ((t.installmentGroup IS NULL OR inv IS NULL) AND t.date BETWEEN :startDate AND :endDate)
    )
  ORDER BY t.date DESC
  """)
List<Transaction> findAllByTenantWithFilters(
    @Param("tenant")    Tenant tenant,
    @Param("accountId") UUID accountId,
    @Param("status")    TransactionStatus status,
    @Param("type")      TransactionType type,
    @Param("startDate") LocalDate startDate,
    @Param("endDate")   LocalDate endDate
);
```

> **Por que `(:param IS NULL OR condição)`?** É o idioma JPQL para parâmetros opcionais. Quando o valor é `null`, o banco avalia `null IS NULL` como `true`, o que torna a cláusula AND transparente. Cada filtro é independente — você pode combinar qualquer subconjunto.
>
> **Por que dois `LEFT JOIN FETCH`?** O `FETCH` carrega as associações em um único `SELECT`, evitando o problema N+1 onde Hibernate emitiria uma query por transação para buscar `category`, `account`, etc. O `LEFT JOIN FETCH t.invoice inv` é obrigatório (não `t.invoice.dueDate` direto) para que o Hibernate gere um `LEFT JOIN` em vez de `INNER JOIN` — isso evita excluir transações sem fatura.
>
> **Lógica de data:** parcelas de cartão (`installmentGroup IS NOT NULL` e `inv IS NOT NULL`) são filtradas pelo `inv.dueDate` — que é a data de impacto no orçamento. Demais transações (incluindo avulsas de cartão) usam `t.date` — data real da compra, que é o que aparece na coluna "Data" do frontend.

- [ ] **Step 2.2: Atualizar `TransactionService.findAll()` com novos parâmetros**

No `TransactionService.java`, substituir o método `findAll` existente:

```java
@Transactional(readOnly = true)
public List<TransactionResponseDTO> findAll(User user, UUID invoiceId, UUID accountId,
        TransactionStatus status, TransactionType type, LocalDate startDate, LocalDate endDate) {
    if (invoiceId != null) {
        Invoice invoice = invoiceService.findByIdAndTenant(invoiceId, user.getTenant());
        return repository.findAllByTenantAndInvoiceWithDetails(user.getTenant(), invoice)
                .stream().map(TransactionResponseDTO::fromEntity).toList();
    }
    return repository.findAllByTenantWithFilters(
                    user.getTenant(), accountId, status, type, startDate, endDate)
            .stream()
            .sorted(Comparator.comparing(this::effectiveSortDate, Comparator.reverseOrder()))
            .map(TransactionResponseDTO::fromEntity)
            .toList();
}
```

> **Por que `@Transactional(readOnly = true)`?** Essa annotation instrui o Hibernate a não fazer flush da sessão antes de executar a query e permite ao banco de dados usar otimizações para operações de leitura (ex: usar replicas de leitura). Para queries, sempre use `readOnly = true`.

- [ ] **Step 2.3: Atualizar `TransactionController.listTransactions()` para implementar a nova interface**

No `TransactionController.java`, substituir o método `listTransactions`:

```java
@Override
@GetMapping
public ResponseEntity<List<TransactionResponseDTO>> listTransactions(
        @RequestParam(value = "invoiceId", required = false) UUID invoiceId,
        @RequestParam(value = "accountId", required = false) UUID accountId,
        @RequestParam(value = "status",    required = false) TransactionStatus status,
        @RequestParam(value = "type",      required = false) TransactionType type,
        @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(value = "endDate",   required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
    return ResponseEntity.ok(
            service.findAll(getAuthenticatedUser(), invoiceId, accountId, status, type, startDate, endDate));
}
```

Adicionar no bloco de imports do controller:
```java
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
```

> **Por que `@DateTimeFormat(iso = ISO.DATE)`?** Para query params de tipo `LocalDate`, o Spring precisa saber o formato esperado da string. `ISO.DATE` corresponde ao formato `YYYY-MM-DD` definido na spec OpenAPI com `format: date`.

- [ ] **Step 2.4: Verificar que o projeto compila**

```bash
cd backend && ./mvnw compile
```

Resultado esperado: `BUILD SUCCESS`. Se houver erros de compilação, eles indicarão onde a interface gerada e a implementação divergem.

- [ ] **Step 2.5: Escrever os testes de filtro (TDD — adicionar ao `TransactionControllerTest`)**

No `TransactionControllerTest.java`, adicionar:

1. Um método helper `buildSampleDto()` para evitar repetição do construtor verboso:

```java
private TransactionResponseDTO buildSampleDto(String description) {
    return new TransactionResponseDTO(
            UUID.randomUUID(), description, new BigDecimal("100.00"), LocalDate.now(),
            null, null, null, null, null, null, null, false,
            null, null, null, null, null, null, null, null, null, null);
}
```

2. Atualizar o mock do teste existente `shouldListAllTransactions` para a nova assinatura de 7 parâmetros:

```java
// Antes:
when(transactionService.findAll(any(User.class), any())).thenReturn(List.of(responseDTO));

// Depois (isNull() para todos os novos params — equivale a "sem filtro"):
when(transactionService.findAll(
        any(User.class), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(List.of(buildSampleDto("Test")));
```

Adicionar o import: `import static org.mockito.Mockito.isNull;`

3. Adicionar três novos testes de filtro:

```java
@Test
@DisplayName("Deve repassar accountId ao service quando informado")
void shouldPassAccountIdFilter() throws Exception {
    UUID accountId = UUID.randomUUID();
    when(transactionService.findAll(
            any(User.class), isNull(), eq(accountId), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(List.of(buildSampleDto("Nubank")));

    mockMvc.perform(get("/api/transactions")
                    .param("accountId", accountId.toString())
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].description").value("Nubank"));
}

@Test
@DisplayName("Deve repassar status ao service quando informado")
void shouldPassStatusFilter() throws Exception {
    when(transactionService.findAll(
            any(User.class), isNull(), isNull(), eq(TransactionStatus.PENDING), isNull(), isNull(), isNull()))
            .thenReturn(List.of(buildSampleDto("Pendente")));

    mockMvc.perform(get("/api/transactions")
                    .param("status", "PENDING")
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].description").value("Pendente"));
}

@Test
@DisplayName("Deve repassar período ao service quando informado")
void shouldPassDateRangeFilter() throws Exception {
    LocalDate start = LocalDate.of(2026, 6, 1);
    LocalDate end   = LocalDate.of(2026, 6, 30);
    when(transactionService.findAll(
            any(User.class), isNull(), isNull(), isNull(), isNull(), eq(start), eq(end)))
            .thenReturn(List.of(buildSampleDto("Junho")));

    mockMvc.perform(get("/api/transactions")
                    .param("startDate", "2026-06-01")
                    .param("endDate",   "2026-06-30")
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].description").value("Junho"));
}
```

Adicionar imports:
```java
import static org.mockito.Mockito.eq;
import com.fintech.api.domain.enums.TransactionStatus;
```

- [ ] **Step 2.6: Rodar os testes e verificar que passam**

```bash
cd backend && ./mvnw test -pl . -Dtest=TransactionControllerTest
```

Resultado esperado: `Tests run: 5, Failures: 0, Errors: 0`. Se algum teste falhar:
- Erro de compilação: verificar imports e assinatura do método
- `UnsatisfiedStubbingException`: verificar se o argumento matcher (`eq`, `isNull`, `any`) bate com o que o controller passa ao service

- [ ] **Step 2.7: Commit**

```bash
git add backend/src/main/java/com/fintech/api/repository/TransactionRepository.java \
        backend/src/main/java/com/fintech/api/service/TransactionService.java \
        backend/src/main/java/com/fintech/api/controller/TransactionController.java \
        backend/src/test/java/com/fintech/api/controller/TransactionControllerTest.java
git commit -m "feat(transactions): adiciona filtros por conta, status, tipo e período ao endpoint GET /api/transactions"
```

---

## Task 3: Frontend utils — TDD para groupByEffectiveMonth e helpers de período

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.spec.ts`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts`

- [ ] **Step 3.1: Escrever os testes que vão falhar**

No arquivo `transaction-list.spec.ts`, adicionar os seguintes `describe` blocos após os existentes (os imports de `transaction-list.utils` já estão lá):

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import {
  buildDisplayRows, DisplayRow, InstallmentGroupInfo,
  effectiveMonth, groupByEffectiveMonth, resolveMonthKey, monthBounds, formatMonthLabel,
  PeriodGroup,
} from './transaction-list.utils';

// --- Novos testes ---

describe('effectiveMonth', () => {
  it('parcela de cartão usa mês do invoiceDueDate', () => {
    const t: any = { installmentGroupId: 'g1', invoiceDueDate: '2026-07-10', date: '2026-06-01' };
    expect(effectiveMonth(t)).toBe('2026-07');
  });

  it('transação avulsa usa mês de date', () => {
    const t: any = { installmentGroupId: null, date: '2026-06-15' };
    expect(effectiveMonth(t)).toBe('2026-06');
  });

  it('transação avulsa de cartão (sem installmentGroupId, mesmo com invoiceDueDate) usa date', () => {
    const t: any = { installmentGroupId: null, invoiceDueDate: '2026-07-10', date: '2026-06-15' };
    expect(effectiveMonth(t)).toBe('2026-06');
  });
});

describe('groupByEffectiveMonth', () => {
  it('retorna grupos em ordem decrescente de mês', () => {
    const txs: any[] = [
      { id: '1', installmentGroupId: null, date: '2026-05-10', type: 'EXPENSE', amount: 100, status: 'PAID' },
      { id: '2', installmentGroupId: null, date: '2026-06-15', type: 'INCOME',  amount: 500, status: 'PAID' },
    ];
    const groups = groupByEffectiveMonth(txs);
    expect(groups[0].key).toBe('2026-06');
    expect(groups[1].key).toBe('2026-05');
  });

  it('calcula totalIncome, totalExpense e balance por grupo', () => {
    const txs: any[] = [
      { id: '1', installmentGroupId: null, date: '2026-06-10', type: 'INCOME',  amount: 1000, status: 'PAID' },
      { id: '2', installmentGroupId: null, date: '2026-06-20', type: 'EXPENSE', amount: 300,  status: 'PAID' },
    ];
    const [group] = groupByEffectiveMonth(txs);
    expect(group.totalIncome).toBe(1000);
    expect(group.totalExpense).toBe(300);
    expect(group.balance).toBe(700);
  });

  it('parcelas de cartão agrupam pelo mês do invoiceDueDate', () => {
    const txs: any[] = [
      { id: '1', installmentGroupId: 'g1', invoiceDueDate: '2026-07-15', date: '2026-06-01', type: 'EXPENSE', amount: 200, status: 'PENDING' },
    ];
    const groups = groupByEffectiveMonth(txs);
    expect(groups[0].key).toBe('2026-07');
  });
});

describe('resolveMonthKey', () => {
  it('retorna chave YYYY-MM quando o range é exatamente um mês', () => {
    expect(resolveMonthKey('2026-06-01', '2026-06-30')).toBe('2026-06');
  });

  it('retorna "custom" quando range é intervalo arbitrário', () => {
    expect(resolveMonthKey('2026-06-15', '2026-07-15')).toBe('custom');
  });

  it('retorna "" quando startDate ou endDate é null', () => {
    expect(resolveMonthKey(null, '2026-06-30')).toBe('');
    expect(resolveMonthKey('2026-06-01', null)).toBe('');
  });
});

describe('monthBounds', () => {
  it('retorna primeiro e último dia de junho de 2026', () => {
    const bounds = monthBounds('2026-06');
    expect(bounds.startDate).toBe('2026-06-01');
    expect(bounds.endDate).toBe('2026-06-30');
  });

  it('retorna último dia correto para fevereiro 2026 (não-bissexto)', () => {
    const bounds = monthBounds('2026-02');
    expect(bounds.endDate).toBe('2026-02-28');
  });
});

describe('buildDisplayRows com groupByPeriod', () => {
  it('insere uma linha period-header antes das transações de cada mês', () => {
    const txs: any[] = [
      { id: '1', installmentGroupId: null, date: '2026-06-10', type: 'INCOME',  amount: 500,  status: 'PAID' },
      { id: '2', installmentGroupId: null, date: '2026-05-05', type: 'EXPENSE', amount: 100,  status: 'PAID' },
    ];
    const rows = buildDisplayRows(txs, new Set(), true);
    expect(rows[0].kind).toBe('period-header');
    expect((rows[0] as any).key).toBe('2026-06');
    expect(rows[1].kind).not.toBe('period-header');
    expect(rows[2].kind).toBe('period-header');
    expect((rows[2] as any).key).toBe('2026-05');
  });

  it('sem groupByPeriod não insere period-header', () => {
    const txs: any[] = [
      { id: '1', installmentGroupId: null, date: '2026-06-10', type: 'INCOME', amount: 500, status: 'PAID' },
    ];
    const rows = buildDisplayRows(txs, new Set());
    expect(rows.every(r => r.kind !== 'period-header')).toBe(true);
  });
});
```

- [ ] **Step 3.2: Rodar os testes e verificar que falham**

```bash
cd frontend && npm test -- --reporter=verbose 2>&1 | grep -E "FAIL|PASS|✓|×"
```

Resultado esperado: múltiplos `FAIL` nos novos describes (símbolos não exportados). Os testes existentes devem continuar passando.

- [ ] **Step 3.3: Implementar as novas funções em `transaction-list.utils.ts`**

Adicionar ao início do arquivo (antes das tipos existentes), o novo tipo `PeriodGroup` e a nova variante de `DisplayRow`:

```ts
export type PeriodGroup = {
  key: string;
  label: string;
  transactions: TransactionResponseDTO[];
  totalIncome: number;
  totalExpense: number;
  balance: number;
  isCurrentMonth: boolean;
};
```

Atualizar o tipo `DisplayRow` para incluir `period-header`:

```ts
export type DisplayRow =
  | { kind: 'single';          data: TransactionResponseDTO }
  | { kind: 'installment';     data: TransactionResponseDTO; group: InstallmentGroupInfo; isExpanded: boolean }
  | { kind: 'installment-detail'; data: TransactionResponseDTO; group: InstallmentGroupInfo }
  | { kind: 'period-header';   key: string; label: string; totalIncome: number; totalExpense: number; balance: number };
```

Adicionar as novas funções exportadas após o `buildDisplayRows` existente:

```ts
export function effectiveMonth(t: TransactionResponseDTO): string {
  if (t.installmentGroupId && t.invoiceDueDate) {
    return t.invoiceDueDate.substring(0, 7);
  }
  return (t.date ?? '').substring(0, 7);
}

export function formatMonthLabel(key: string): string {
  const [year, month] = key.split('-').map(Number);
  const date = new Date(year, month - 1, 1);
  const label = date.toLocaleDateString('pt-BR', { month: 'long', year: 'numeric' });
  return label.charAt(0).toUpperCase() + label.slice(1);
}

export function groupByEffectiveMonth(transactions: TransactionResponseDTO[]): PeriodGroup[] {
  const now = new Date();
  const currentKey = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;

  const map = new Map<string, TransactionResponseDTO[]>();
  for (const t of transactions) {
    const key = effectiveMonth(t);
    if (!key) continue;
    const bucket = map.get(key) ?? [];
    bucket.push(t);
    map.set(key, bucket);
  }

  return [...map.entries()]
    .sort(([a], [b]) => b.localeCompare(a))
    .map(([key, txs]) => ({
      key,
      label: formatMonthLabel(key),
      transactions: txs,
      totalIncome:  txs.filter(t => t.type === 'INCOME').reduce((s, t) => s + (t.amount ?? 0), 0),
      totalExpense: txs.filter(t => t.type === 'EXPENSE').reduce((s, t) => s + (t.amount ?? 0), 0),
      balance:      txs.filter(t => t.type === 'INCOME').reduce((s, t) => s + (t.amount ?? 0), 0)
                  - txs.filter(t => t.type === 'EXPENSE').reduce((s, t) => s + (t.amount ?? 0), 0),
      isCurrentMonth: key === currentKey,
    }));
}

export function resolveMonthKey(startDate: string | null, endDate: string | null): string {
  if (!startDate || !endDate) return '';
  const [sy, sm] = startDate.split('-').map(Number);
  const [ey, em] = endDate.split('-').map(Number);
  const firstDay = new Date(sy, sm - 1, 1);
  const lastDay  = new Date(sy, sm, 0);
  const first = `${firstDay.getFullYear()}-${String(firstDay.getMonth() + 1).padStart(2, '0')}-${String(firstDay.getDate()).padStart(2, '0')}`;
  const last  = `${lastDay.getFullYear()}-${String(lastDay.getMonth() + 1).padStart(2, '0')}-${String(lastDay.getDate()).padStart(2, '0')}`;
  if (first === startDate && last === endDate && sy === ey && sm === em) {
    return `${sy}-${String(sm).padStart(2, '0')}`;
  }
  return 'custom';
}

export function monthBounds(key: string): { startDate: string; endDate: string } {
  const [year, month] = key.split('-').map(Number);
  const firstDay = new Date(year, month - 1, 1);
  const lastDay  = new Date(year, month, 0);
  const pad = (n: number) => String(n).padStart(2, '0');
  return {
    startDate: `${firstDay.getFullYear()}-${pad(firstDay.getMonth() + 1)}-${pad(firstDay.getDate())}`,
    endDate:   `${lastDay.getFullYear()}-${pad(lastDay.getMonth() + 1)}-${pad(lastDay.getDate())}`,
  };
}
```

- [ ] **Step 3.4: Refatorar `buildDisplayRows` para aceitar `groupByPeriod`**

Extrair a lógica atual de `buildDisplayRows` para uma função interna `buildFlatRows` e modificar `buildDisplayRows` para aceitar o terceiro parâmetro:

```ts
// Renomear a lógica interna para buildFlatRows:
function buildFlatRows(
  transactions: TransactionResponseDTO[],
  expandedIds: Set<string>
): DisplayRow[] {
  const groupsMap = new Map<string, TransactionResponseDTO[]>();
  for (const t of transactions) {
    if (t.installmentGroupId) {
      const existing = groupsMap.get(t.installmentGroupId) ?? [];
      existing.push(t);
      groupsMap.set(t.installmentGroupId, existing);
    }
  }

  const groupInfoMap = new Map<string, InstallmentGroupInfo>();
  for (const [groupId, txs] of groupsMap) {
    groupInfoMap.set(groupId, {
      groupId,
      description: txs[0]?.installmentGroupDescription ?? txs[0]?.description ?? '',
      totalInstallments: txs.length,
      paidInstallments: txs.filter(tx => tx.status === 'PAID').length,
      installmentAmount: txs[0]?.amount ?? 0,
      categoryName: txs[0]?.categoryName ?? null,
      accountName: txs[0]?.accountName ?? null,
      transactions: txs,
    });
  }

  return transactions.flatMap(t => {
    if (t.installmentGroupId) {
      const group = groupInfoMap.get(t.installmentGroupId)!;
      const isExpanded = expandedIds.has(t.id);
      const rows: DisplayRow[] = [{ kind: 'installment', data: t, group, isExpanded }];
      if (isExpanded) rows.push({ kind: 'installment-detail', data: t, group });
      return rows;
    }
    return [{ kind: 'single', data: t }];
  });
}

// Atualizar buildDisplayRows (a função exportada):
export function buildDisplayRows(
  transactions: TransactionResponseDTO[],
  expandedIds: Set<string>,
  groupByPeriod = false
): DisplayRow[] {
  if (!groupByPeriod) {
    return buildFlatRows(transactions, expandedIds);
  }
  const groups = groupByEffectiveMonth(transactions);
  return groups.flatMap(group => [
    {
      kind: 'period-header' as const,
      key: group.key,
      label: group.label,
      totalIncome: group.totalIncome,
      totalExpense: group.totalExpense,
      balance: group.balance,
    },
    ...buildFlatRows(group.transactions, expandedIds),
  ]);
}
```

- [ ] **Step 3.5: Rodar os testes e verificar que todos passam**

```bash
cd frontend && npm test -- --reporter=verbose 2>&1 | grep -E "FAIL|PASS|✓|×|Tests"
```

Resultado esperado: todos os testes passam. Se algum falhar, o erro mostrará o valor recebido vs. esperado.

- [ ] **Step 3.6: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts \
        frontend/src/app/features/transaction/transaction-list/transaction-list.spec.ts
git commit -m "feat(transaction-list): adiciona groupByEffectiveMonth, helpers de período e period-header row"
```

---

## Task 4: Frontend — TransactionFiltersComponent

**Files:**
- Create: `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.types.ts`
- Create: `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.ts`
- Create: `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.html`
- Create: `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.scss`

- [ ] **Step 4.1: Criar `transaction-filters.types.ts`**

```ts
export interface TransactionFilters {
  accountId: string | null;
  status: 'PENDING' | 'PAID' | 'CANCELLED' | null;
  type: 'INCOME' | 'EXPENSE' | null;
  startDate: string | null;
  endDate: string | null;
  groupByPeriod: boolean;
}

export const DEFAULT_FILTERS: TransactionFilters = {
  accountId: null,
  status: null,
  type: null,
  startDate: null,
  endDate: null,
  groupByPeriod: false,
};
```

- [ ] **Step 4.2: Criar `transaction-filters.ts`**

```ts
import { Component, input, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { AccountResponse } from '../../../../core/api/fintechSaaSAPI.schemas';
import { TransactionFilters, DEFAULT_FILTERS } from './transaction-filters.types';
import { resolveMonthKey, monthBounds, formatMonthLabel } from '../transaction-list.utils';

@Component({
  selector: 'app-transaction-filters',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatFormFieldModule, MatSelectModule, MatInputModule,
    MatButtonModule, MatIconModule, MatSlideToggleModule,
  ],
  templateUrl: './transaction-filters.html',
  styleUrl: './transaction-filters.scss',
})
export class TransactionFiltersComponent {
  accounts    = input<AccountResponse[]>([]);
  filterChange = output<TransactionFilters>();

  accountId     = signal<string | null>(null);
  status        = signal<'PENDING' | 'PAID' | 'CANCELLED' | null>(null);
  type          = signal<'INCOME' | 'EXPENSE' | null>(null);
  startDate     = signal<string | null>(null);
  endDate       = signal<string | null>(null);
  groupByPeriod = signal(false);
  monthLabel    = signal('');

  onAccountChange(val: string | null): void {
    this.accountId.set(val);
    this.emit();
  }

  onStatusChange(val: 'PENDING' | 'PAID' | 'CANCELLED' | null): void {
    this.status.set(val);
    this.emit();
  }

  onTypeChange(val: 'INCOME' | 'EXPENSE' | null): void {
    this.type.set(val);
    this.emit();
  }

  onStartDateChange(val: string): void {
    this.startDate.set(val || null);
    this.syncMonthLabel();
    this.emit();
  }

  onEndDateChange(val: string): void {
    this.endDate.set(val || null);
    this.syncMonthLabel();
    this.emit();
  }

  onGroupByPeriodChange(val: boolean): void {
    this.groupByPeriod.set(val);
    this.emit();
  }

  goToPreviousMonth(): void {
    const key = this.resolveCurrentKey();
    const [year, month] = key.split('-').map(Number);
    const prev = new Date(year, month - 2, 1);
    this.applyMonth(`${prev.getFullYear()}-${String(prev.getMonth() + 1).padStart(2, '0')}`);
  }

  goToNextMonth(): void {
    const key = this.resolveCurrentKey();
    const [year, month] = key.split('-').map(Number);
    const next = new Date(year, month, 1);
    this.applyMonth(`${next.getFullYear()}-${String(next.getMonth() + 1).padStart(2, '0')}`);
  }

  clearFilters(): void {
    this.accountId.set(null);
    this.status.set(null);
    this.type.set(null);
    this.startDate.set(null);
    this.endDate.set(null);
    this.monthLabel.set('');
    this.emit();
  }

  private applyMonth(key: string): void {
    const bounds = monthBounds(key);
    this.startDate.set(bounds.startDate);
    this.endDate.set(bounds.endDate);
    this.monthLabel.set(formatMonthLabel(key));
    this.emit();
  }

  private syncMonthLabel(): void {
    const key = resolveMonthKey(this.startDate(), this.endDate());
    if (!key) {
      this.monthLabel.set('');
    } else if (key === 'custom') {
      this.monthLabel.set('Personalizado');
    } else {
      this.monthLabel.set(formatMonthLabel(key));
    }
  }

  private resolveCurrentKey(): string {
    const key = resolveMonthKey(this.startDate(), this.endDate());
    if (key && key !== 'custom') return key;
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  }

  private emit(): void {
    this.filterChange.emit({
      accountId:     this.accountId(),
      status:        this.status(),
      type:          this.type(),
      startDate:     this.startDate(),
      endDate:       this.endDate(),
      groupByPeriod: this.groupByPeriod(),
    });
  }
}
```

- [ ] **Step 4.3: Criar `transaction-filters.html`**

```html
<div class="filters-panel">
  <div class="filter-row">

    <mat-form-field appearance="outline" class="filter-field">
      <mat-label>Conta</mat-label>
      <mat-select [ngModel]="accountId()" (ngModelChange)="onAccountChange($event)">
        <mat-option [value]="null">Todas</mat-option>
        @for (account of accounts(); track account.id) {
          <mat-option [value]="account.id">{{ account.name }}</mat-option>
        }
      </mat-select>
    </mat-form-field>

    <mat-form-field appearance="outline" class="filter-field">
      <mat-label>Status</mat-label>
      <mat-select [ngModel]="status()" (ngModelChange)="onStatusChange($event)">
        <mat-option [value]="null">Todos</mat-option>
        <mat-option value="PENDING">Pendente</mat-option>
        <mat-option value="PAID">Pago</mat-option>
        <mat-option value="CANCELLED">Cancelado</mat-option>
      </mat-select>
    </mat-form-field>

    <mat-form-field appearance="outline" class="filter-field">
      <mat-label>Tipo</mat-label>
      <mat-select [ngModel]="type()" (ngModelChange)="onTypeChange($event)">
        <mat-option [value]="null">Todos</mat-option>
        <mat-option value="INCOME">Receita</mat-option>
        <mat-option value="EXPENSE">Despesa</mat-option>
      </mat-select>
    </mat-form-field>

    <div class="period-group">
      <div class="month-nav">
        <button mat-icon-button type="button" (click)="goToPreviousMonth()" matTooltip="Mês anterior">
          <mat-icon>chevron_left</mat-icon>
        </button>
        <span class="month-label">{{ monthLabel() || 'Todos os períodos' }}</span>
        <button mat-icon-button type="button" (click)="goToNextMonth()" matTooltip="Próximo mês">
          <mat-icon>chevron_right</mat-icon>
        </button>
      </div>
      <div class="date-range">
        <mat-form-field appearance="outline" class="date-field">
          <mat-label>De</mat-label>
          <input matInput type="date" [ngModel]="startDate() ?? ''" (ngModelChange)="onStartDateChange($event)">
        </mat-form-field>
        <mat-form-field appearance="outline" class="date-field">
          <mat-label>Até</mat-label>
          <input matInput type="date" [ngModel]="endDate() ?? ''" (ngModelChange)="onEndDateChange($event)">
        </mat-form-field>
      </div>
    </div>

  </div>

  <div class="filter-actions">
    <mat-slide-toggle
      [ngModel]="groupByPeriod()"
      (ngModelChange)="onGroupByPeriodChange($event)">
      Agrupar por período
    </mat-slide-toggle>
    <button mat-button (click)="clearFilters()">
      <mat-icon>clear_all</mat-icon>
      Limpar filtros
    </button>
  </div>
</div>
```

- [ ] **Step 4.4: Criar `transaction-filters.scss`**

```scss
.filters-panel {
  padding: 16px 20px;
  background: #fafafa;
  border-bottom: 1px solid rgba(0, 0, 0, 0.08);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-start;
}

.filter-field {
  min-width: 160px;
  flex: 1 1 160px;

  ::ng-deep .mat-mdc-form-field-subscript-wrapper {
    display: none;
  }
}

.period-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 2 1 320px;
}

.month-nav {
  display: flex;
  align-items: center;
  gap: 6px;

  .month-label {
    font-size: 0.9rem;
    font-weight: 500;
    color: #333;
    min-width: 130px;
    text-align: center;
  }
}

.date-range {
  display: flex;
  gap: 8px;

  .date-field {
    flex: 1;

    ::ng-deep .mat-mdc-form-field-subscript-wrapper {
      display: none;
    }
  }
}

.filter-actions {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
```

- [ ] **Step 4.5: Verificar que o TypeScript compila sem erros**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -30
```

Resultado esperado: sem output (sem erros). Se houver erros de tipo, eles indicarão qual campo está incompatível.

- [ ] **Step 4.6: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-list/transaction-filters/
git commit -m "feat(transaction-filters): cria componente de filtros com seletor de mês integrado"
```

---

## Task 5: Frontend — TransactionList integration

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.ts`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.html`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.scss`

- [ ] **Step 5.1: Atualizar `transaction-list.ts`**

Substituir o conteúdo completo do arquivo:

```ts
import { Component, inject, OnInit, signal, computed, untracked } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { forkJoin } from 'rxjs';

import { TransactionsService } from '../../../core/api/transactions/transactions.service';
import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { InstallmentGroupsService } from '../../../core/api/installment-groups/installment-groups.service';
import { TransfersService } from '../../../core/api/transfers/transfers.service';
import { TransactionResponseDTO, AccountResponse } from '../../../core/api/fintechSaaSAPI.schemas';
import { ConfirmationDialogComponent } from '../../../components/confirmation-dialog/confirmation-dialog';
import { DeleteInstallmentDialogComponent, DeleteInstallmentDialogResult } from './delete-installment-dialog/delete-installment-dialog';
import { TransactionFiltersComponent } from './transaction-filters/transaction-filters';
import { TransactionFilters, DEFAULT_FILTERS } from './transaction-filters/transaction-filters.types';
import { buildDisplayRows, InstallmentGroupInfo, DisplayRow } from './transaction-list.utils';
export { buildDisplayRows } from './transaction-list.utils';
export type { InstallmentGroupInfo, DisplayRow } from './transaction-list.utils';

@Component({
  selector: 'app-transaction-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatDialogModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatProgressBarModule,
    CurrencyPipe,
    DatePipe,
    TransactionFiltersComponent,
  ],
  templateUrl: './transaction-list.html',
  styleUrl: './transaction-list.scss'
})
export class TransactionList implements OnInit {
  private service        = inject(TransactionsService);
  private accountService = inject(AccountsService);
  private groupService   = inject(InstallmentGroupsService);
  private transferService = inject(TransfersService);
  private router  = inject(Router);
  private dialog  = inject(MatDialog);
  private snackBar = inject(MatSnackBar);

  transactions         = signal<TransactionResponseDTO[]>([]);
  accounts             = signal<AccountResponse[]>([]);
  expandedTransactions = signal(new Set<string>());
  filters              = signal<TransactionFilters>(DEFAULT_FILTERS);
  showFilters          = signal(false);

  displayedColumns = ['description', 'amount', 'date', 'type', 'status', 'category', 'account', 'actions'];

  displayRows = computed(() =>
    buildDisplayRows(this.transactions(), this.expandedTransactions(), this.filters().groupByPeriod)
  );

  activeFilterChips = computed((): Array<{ label: string; field: string }> => {
    const f = this.filters();
    const chips: Array<{ label: string; field: string }> = [];
    if (f.accountId) {
      const account = this.accounts().find(a => a.id === f.accountId);
      chips.push({ label: `Conta: ${account?.name ?? f.accountId}`, field: 'accountId' });
    }
    if (f.status) {
      const labels: Record<string, string> = { PENDING: 'Pendente', PAID: 'Pago', CANCELLED: 'Cancelado' };
      chips.push({ label: `Status: ${labels[f.status]}`, field: 'status' });
    }
    if (f.type) {
      const labels: Record<string, string> = { INCOME: 'Receita', EXPENSE: 'Despesa' };
      chips.push({ label: `Tipo: ${labels[f.type]}`, field: 'type' });
    }
    if (f.startDate || f.endDate) {
      chips.push({ label: `Período: ${f.startDate ?? '?'} – ${f.endDate ?? '?'}`, field: 'period' });
    }
    return chips;
  });

  isDataRow      = (_: number, row: DisplayRow) => row.kind === 'single' || row.kind === 'installment';
  isDetailRow    = (_: number, row: DisplayRow) => row.kind === 'installment-detail';
  isPeriodHeader = (_: number, row: DisplayRow) => row.kind === 'period-header';

  ngOnInit(): void {
    forkJoin({
      accounts: this.accountService.listAccounts(),
      transactions: this.service.listTransactions(),
    }).subscribe({
      next: ({ accounts, transactions }) => {
        this.accounts.set(accounts);
        this.transactions.set(transactions);
      },
      error: () => this.snackBar.open('Erro ao carregar dados.', 'Fechar', { duration: 5000 }),
    });
  }

  onFilterChange(newFilters: TransactionFilters): void {
    this.filters.set(newFilters);
    untracked(() => this.loadTransactions(newFilters));
  }

  clearFilterChip(field: string): void {
    this.filters.update(f => {
      if (field === 'accountId') return { ...f, accountId: null };
      if (field === 'status')    return { ...f, status: null };
      if (field === 'type')      return { ...f, type: null };
      if (field === 'period')    return { ...f, startDate: null, endDate: null };
      return f;
    });
    untracked(() => this.loadTransactions(this.filters()));
  }

  loadTransactions(f: TransactionFilters = this.filters()): void {
    this.service.listTransactions({
      accountId:  f.accountId  ?? undefined,
      status:     f.status     ?? undefined,
      type:       f.type       ?? undefined,
      startDate:  f.startDate  ?? undefined,
      endDate:    f.endDate    ?? undefined,
    }).subscribe({
      next:  (data) => this.transactions.set(data),
      error: () => this.snackBar.open('Erro ao carregar transações.', 'Fechar', { duration: 5000 }),
    });
  }

  toggleExpand(id: string): void {
    this.expandedTransactions.update(set => {
      const next = new Set(set);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  }

  onEdit(t: TransactionResponseDTO | undefined): void {
    if (!t) return;
    this.router.navigate(['/transactions', t.id]);
  }

  onDeleteGroup(group: InstallmentGroupInfo, event: Event): void {
    event.stopPropagation();
    const dialogRef = this.dialog.open(ConfirmationDialogComponent, {
      width: '400px',
      data: {
        title: 'Excluir grupo de parcelamento',
        message: `Deseja excluir o grupo "${group.description}"? Parcelas já pagas serão mantidas no histórico.`,
        confirmText: 'Sim, excluir pendentes',
      },
    });
    dialogRef.afterClosed().subscribe(confirmed => {
      if (confirmed !== true) return;
      this.groupService.deleteInstallmentGroup(group.groupId).subscribe({
        next: (result) => {
          const msg = result.skippedPaid > 0
            ? `${result.deleted} parcela(s) excluída(s). ${result.skippedPaid} pagas foram mantidas.`
            : `${result.deleted} parcela(s) excluída(s).`;
          this.snackBar.open(msg, 'OK', { duration: 4000 });
          this.loadTransactions();
        },
        error: () => this.snackBar.open('Erro ao excluir grupo.', 'Fechar', { duration: 5000 }),
      });
    });
  }

  onDelete(t: TransactionResponseDTO | undefined): void {
    if (!t) return;
    const isInstallment = !!t.installmentGroupId;
    const isTransfer    = !!t.transferId;

    if (!isTransfer && isInstallment) {
      const dialogRef = this.dialog.open(DeleteInstallmentDialogComponent, {
        width: '460px',
        data: { transaction: t },
      });
      dialogRef.afterClosed().subscribe((result: DeleteInstallmentDialogResult | undefined) => {
        if (!result) return;
        this.service.deleteTransaction(t.id, { scope: result.scope }).subscribe({
          next: (res: any) => {
            const msg = res?.skippedPaid > 0
              ? `${res.deleted} parcela(s) excluída(s). ${res.skippedPaid} pagas foram mantidas.`
              : `${res?.deleted ?? 1} parcela(s) excluída(s).`;
            this.snackBar.open(msg, 'OK', { duration: 4000 });
            this.loadTransactions();
          },
          error: () => this.snackBar.open('Erro ao excluir parcela.', 'Fechar', { duration: 5000 }),
        });
      });
      return;
    }

    if (isTransfer) {
      const dialogRef = this.dialog.open(ConfirmationDialogComponent, {
        width: '400px',
        data: {
          title: 'Excluir Transferência',
          message: 'Deseja excluir esta transferência? Os dois lançamentos serão removidos.',
          confirmText: 'Sim, excluir',
        },
      });
      dialogRef.afterClosed().subscribe(confirmed => {
        if (confirmed !== true) return;
        this.transferService.deleteTransfer(t.transferId!).subscribe({
          next: () => { this.snackBar.open('Transferência excluída.', 'OK', { duration: 3000 }); this.loadTransactions(); },
          error: () => this.snackBar.open('Erro ao excluir transferência.', 'Fechar', { duration: 5000 }),
        });
      });
      return;
    }

    const dialogRef = this.dialog.open(ConfirmationDialogComponent, {
      width: '400px',
      data: {
        title: 'Excluir Transação',
        message: `Deseja excluir "${t.description}"? Esta ação não pode ser desfeita.`,
        confirmText: 'Sim, excluir',
      },
    });
    dialogRef.afterClosed().subscribe(confirmed => {
      if (confirmed !== true) return;
      this.service.deleteTransaction(t.id).subscribe({
        next: () => { this.snackBar.open('Transação excluída.', 'OK', { duration: 3000 }); this.loadTransactions(); },
        error: () => this.snackBar.open('Erro ao excluir transação.', 'Fechar', { duration: 5000 }),
      });
    });
  }

  typeLabel(t: TransactionResponseDTO | undefined): string {
    if (!t) return '';
    if (t.transferId) return 'Transferência';
    const labels: Record<string, string> = { INCOME: 'Receita', EXPENSE: 'Despesa' };
    return labels[t.type ?? ''] ?? (t.type ?? '');
  }

  statusLabel(status: string): string {
    const labels: Record<string, string> = { PENDING: 'Pendente', PAID: 'Pago', CANCELLED: 'Cancelado' };
    return labels[status] ?? status;
  }

  invoiceChipClass(status: string | undefined): string {
    const map: Record<string, string> = { OPEN: 'invoice-open', CLOSED: 'invoice-closed', PAID: 'invoice-paid' };
    return 'invoice-chip ' + (map[status ?? ''] ?? '');
  }

  invoiceLabel(t: TransactionResponseDTO | undefined): string | null {
    if (!t?.invoiceId || !t.invoiceDueDate) return null;
    const d = new Date(t.invoiceDueDate + 'T00:00:00');
    const month = d.toLocaleDateString('pt-BR', { month: 'short', year: 'numeric' });
    return `Fatura ${month}`;
  }
}
```

- [ ] **Step 5.2: Atualizar `transaction-list.html`**

Substituir o conteúdo completo do arquivo:

```html
<div class="page-container">
  <header class="page-header">
    <div>
      <h1>Transações</h1>
      <p class="subtitle">Acompanhe suas receitas, despesas e transferências</p>
    </div>
    <div class="header-actions">
      <button mat-stroked-button (click)="showFilters.update(v => !v)">
        <mat-icon>tune</mat-icon>
        Filtrar
        @if (activeFilterChips().length > 0) {
          <span class="filter-badge">{{ activeFilterChips().length }}</span>
        }
      </button>
      <button mat-flat-button color="primary" routerLink="/transactions/new">
        <mat-icon>add</mat-icon>
        Nova Transação
      </button>
    </div>
  </header>

  <!-- Painel de filtros colapsável -->
  @if (showFilters()) {
    <div class="filters-wrapper mat-elevation-z1">
      <app-transaction-filters
        [accounts]="accounts()"
        (filterChange)="onFilterChange($event)">
      </app-transaction-filters>
    </div>
  }

  <!-- Chips de filtros ativos (visíveis mesmo com painel fechado) -->
  @if (activeFilterChips().length > 0) {
    <div class="active-chips">
      @for (chip of activeFilterChips(); track chip.field) {
        <span class="filter-chip">
          {{ chip.label }}
          <button mat-icon-button class="chip-remove" (click)="clearFilterChip(chip.field)" matTooltip="Remover filtro">
            <mat-icon>close</mat-icon>
          </button>
        </span>
      }
    </div>
  }

  <div class="table-container mat-elevation-z2">
    <table mat-table [dataSource]="displayRows()">

      <!-- Coluna: period-header (linha de grupo — colspan total) -->
      <ng-container matColumnDef="periodHeader">
        <td mat-cell *matCellDef="let row" [attr.colspan]="displayedColumns.length" class="period-header-cell">
          <div class="period-header-content">
            <span class="period-label">{{ $any(row).label }}</span>
            <div class="period-totals">
              <span class="period-income">▲ {{ $any(row).totalIncome | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</span>
              <span class="period-expense">▼ {{ $any(row).totalExpense | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</span>
              <span class="period-balance" [class.positive]="$any(row).balance >= 0" [class.negative]="$any(row).balance < 0">
                = {{ $any(row).balance | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}
              </span>
            </div>
          </div>
        </td>
      </ng-container>

      <!-- Coluna: Descrição -->
      <ng-container matColumnDef="description">
        <th mat-header-cell *matHeaderCellDef>Descrição</th>
        <td mat-cell *matCellDef="let row">
          <span class="description-text">{{ $any(row).data?.description }}</span>
          @if ($any(row).data?.installmentLabel) {
            <span class="installment-badge">{{ $any(row).data.installmentLabel }}</span>
          }
        </td>
      </ng-container>

      <!-- Coluna: Valor -->
      <ng-container matColumnDef="amount">
        <th mat-header-cell *matHeaderCellDef>Valor</th>
        <td mat-cell *matCellDef="let row">
          <span [class]="'amount ' + ($any(row).data?.type ?? '').toLowerCase()">
            {{ $any(row).data?.amount | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}
          </span>
        </td>
      </ng-container>

      <!-- Coluna: Data -->
      <ng-container matColumnDef="date">
        <th mat-header-cell *matHeaderCellDef>Data</th>
        <td mat-cell *matCellDef="let row">
          {{ ($any(row).data?.installmentGroupId && $any(row).data?.invoiceDueDate
              ? $any(row).data?.invoiceDueDate
              : $any(row).data?.date) | date:'dd/MM/yyyy' }}
        </td>
      </ng-container>

      <!-- Coluna: Tipo -->
      <ng-container matColumnDef="type">
        <th mat-header-cell *matHeaderCellDef>Tipo</th>
        <td mat-cell *matCellDef="let row">
          <span [class]="'type-badge type-' + ($any(row).data?.transferId ? 'transfer' : ($any(row).data?.type ?? '').toLowerCase())">
            {{ typeLabel($any(row).data) }}
          </span>
        </td>
      </ng-container>

      <!-- Coluna: Status -->
      <ng-container matColumnDef="status">
        <th mat-header-cell *matHeaderCellDef>Status</th>
        <td mat-cell *matCellDef="let row">
          <span [class]="'status-badge status-' + ($any(row).data?.status ?? '').toLowerCase()">
            {{ statusLabel($any(row).data?.status ?? '') }}
          </span>
        </td>
      </ng-container>

      <!-- Coluna: Categoria -->
      <ng-container matColumnDef="category">
        <th mat-header-cell *matHeaderCellDef>Categoria</th>
        <td mat-cell *matCellDef="let row">
          @if ($any(row).data?.categoryName) {
            <span
              [class.category-archived]="$any(row).data.categoryArchived"
              [matTooltip]="$any(row).data.categoryArchived ? 'Categoria arquivada' : ''">
              {{ $any(row).data.categoryName }}
            </span>
          } @else { — }
        </td>
      </ng-container>

      <!-- Coluna: Conta -->
      <ng-container matColumnDef="account">
        <th mat-header-cell *matHeaderCellDef>Conta</th>
        <td mat-cell *matCellDef="let row">
          <div class="account-cell">
            <span>{{ $any(row).data?.accountName ?? '—' }}</span>
            @if (invoiceLabel($any(row).data); as label) {
              <a [routerLink]="['/invoices', $any(row).data?.invoiceId]"
                 [class]="invoiceChipClass($any(row).data?.invoiceStatus)"
                 style="text-decoration: none;"
                 matTooltip="Ver fatura">{{ label }}</a>
            }
          </div>
        </td>
      </ng-container>

      <!-- Coluna: Ações -->
      <ng-container matColumnDef="actions">
        <th mat-header-cell *matHeaderCellDef></th>
        <td mat-cell *matCellDef="let row">
          <div class="actions-group">
            @if (row.kind === 'installment') {
              <button mat-icon-button
                      [class.expand-active]="$any(row).isExpanded"
                      (click)="toggleExpand($any(row).data?.id); $event.stopPropagation()"
                      matTooltip="Ver detalhes do parcelamento">
                <mat-icon>{{ $any(row).isExpanded ? 'expand_less' : 'expand_more' }}</mat-icon>
              </button>
            }
            <button mat-icon-button color="primary"
                    (click)="onEdit($any(row).data)"
                    [disabled]="!!$any(row).data?.transferId"
                    [matTooltip]="$any(row).data?.transferId ? 'Transferências não podem ser editadas' : 'Editar'">
              <mat-icon>edit</mat-icon>
            </button>
            <button mat-icon-button color="warn" (click)="onDelete($any(row).data)" matTooltip="Excluir">
              <mat-icon>delete</mat-icon>
            </button>
          </div>
        </td>
      </ng-container>

      <!-- Coluna de detalhe do parcelamento -->
      <ng-container matColumnDef="expandedDetail">
        <td mat-cell *matCellDef="let row" [attr.colspan]="displayedColumns.length" class="detail-cell">
          <div class="installment-detail-panel">
            <div class="detail-group-info">
              <span class="detail-group-name">{{ $any(row).group?.description }}</span>
              <span class="detail-group-meta">
                {{ $any(row).group?.totalInstallments }}x
                {{ $any(row).group?.installmentAmount | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}
                &nbsp;·&nbsp;
                {{ $any(row).group?.paidInstallments }}/{{ $any(row).group?.totalInstallments }} pagas
              </span>
            </div>
            <mat-progress-bar
              mode="determinate"
              [value]="($any(row).group?.paidInstallments / $any(row).group?.totalInstallments) * 100"
              class="detail-progress">
            </mat-progress-bar>
            <div class="detail-meta-row">
              <span class="detail-meta-chip">
                <mat-icon class="chip-icon">shopping_cart</mat-icon>
                Compra em {{ $any(row).data?.date | date:'dd/MM/yyyy' }}
              </span>
              @if ($any(row).group?.categoryName) {
                <span class="detail-meta-chip">
                  <mat-icon class="chip-icon">category</mat-icon>
                  {{ $any(row).group.categoryName }}
                </span>
              }
              <span class="detail-meta-chip">
                <mat-icon class="chip-icon">account_balance_wallet</mat-icon>
                {{ $any(row).group?.accountName ?? '—' }}
              </span>
              <button mat-stroked-button color="warn" class="delete-group-btn"
                      (click)="onDeleteGroup($any(row).group, $event)">
                <mat-icon>delete_sweep</mat-icon>
                Excluir pendentes
              </button>
            </div>
          </div>
        </td>
      </ng-container>

      <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
      <tr mat-row *matRowDef="let row; columns: displayedColumns; when: isDataRow" class="element-row"
          [class.installment-row]="row.kind === 'installment'"
          [class.expanded-row]="$any(row).isExpanded">
      </tr>
      <tr mat-row *matRowDef="let row; columns: ['expandedDetail']; when: isDetailRow" class="detail-row"></tr>
      <tr mat-row *matRowDef="let row; columns: ['periodHeader']; when: isPeriodHeader" class="period-header-row"></tr>

      <tr class="mat-row" *matNoDataRow>
        <td class="mat-cell empty-state" [attr.colspan]="displayedColumns.length">
          <mat-icon>receipt_long</mat-icon>
          <p>Nenhuma transação encontrada.</p>
        </td>
      </tr>
    </table>
  </div>
</div>
```

- [ ] **Step 5.3: Adicionar os estilos para filtros, chips e period-header em `transaction-list.scss`**

Adicionar ao final do arquivo existente:

```scss
// --- Header actions ---

.header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.filter-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--mat-sys-primary, #1976d2);
  color: #fff;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
  min-width: 18px;
  height: 18px;
  padding: 0 4px;
  margin-left: 4px;
}

// --- Painel de filtros ---

.filters-wrapper {
  background: #fff;
  border-radius: 8px;
  margin-bottom: 12px;
  overflow: hidden;
}

// --- Chips de filtros ativos ---

.active-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.filter-chip {
  display: inline-flex;
  align-items: center;
  background: #e3f2fd;
  color: #1565c0;
  border-radius: 16px;
  padding: 4px 8px 4px 12px;
  font-size: 0.8rem;
  font-weight: 500;
  gap: 4px;

  .chip-remove {
    width: 20px;
    height: 20px;
    line-height: 20px;

    mat-icon {
      font-size: 14px;
      width: 14px;
      height: 14px;
    }
  }
}

// --- Period header row ---

.period-header-row td {
  padding: 0 !important;
}

.period-header-cell {
  padding: 0 !important;
}

.period-header-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 16px;
  background: #e3f2fd;
  border-top: 2px solid #90caf9;
  border-bottom: 1px solid #90caf9;
}

.period-label {
  font-weight: 600;
  font-size: 0.9rem;
  color: #1565c0;
}

.period-totals {
  display: flex;
  gap: 16px;
  font-size: 0.82rem;
}

.period-income  { color: #2e7d32; }
.period-expense { color: #c62828; }
.period-balance {
  font-weight: 600;
  &.positive { color: #1565c0; }
  &.negative { color: #c62828; }
}
```

- [ ] **Step 5.4: Verificar que o TypeScript compila sem erros**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -40
```

Resultado esperado: sem output. Se houver erros de tipo, eles indicarão o arquivo e a linha exata.

- [ ] **Step 5.5: Rodar o frontend e verificar manualmente**

```bash
cd frontend && npm start
```

Abrir http://localhost:4200/transactions e verificar:

1. Botão "Filtrar" aparece no header
2. Clicar abre o painel de filtros abaixo do header
3. Selecionar uma conta filtra a lista (nova chamada ao backend)
4. O seletor de mês (◀ / ▶) preenche as datas automaticamente
5. Editar as datas manualmente exibe "Personalizado" no label do mês
6. Toggle "Agrupar por período" exibe as linhas de período-header na tabela
7. Chips ativos aparecem quando há filtros; clicar no ✕ remove o filtro
8. "Limpar filtros" remove todos os filtros e recarrega a lista completa

- [ ] **Step 5.6: Rodar os testes e garantir que nada quebrou**

```bash
cd frontend && npm test
```

Resultado esperado: todos os testes passando.

- [ ] **Step 5.7: Commit final**

```bash
git add frontend/src/app/features/transaction/transaction-list/transaction-list.ts \
        frontend/src/app/features/transaction/transaction-list/transaction-list.html \
        frontend/src/app/features/transaction/transaction-list/transaction-list.scss \
        frontend/src/app/core/api/
git commit -m "feat(transaction-list): integra filtros, chips e agrupamento por período (issue #41)"
```
