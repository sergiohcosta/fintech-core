# Spec: Planning Frontend — Ações de Ciclo e Saldos Disponíveis

**Data:** 2026-06-16
**Status:** Aprovado

## Contexto

O backend já implementa todos os endpoints de planejamento (realizar, pular, editar item, reativar template recorrente, saldo disponível diário). O frontend expõe apenas criar, vincular, desvincular e remover itens. Este spec cobre o gap completo.

## Escopo

1. Regenerar cliente Orval (5 novos métodos + 3 novos campos de summary)
2. Wrappers em `PlanningService`
3. 4º card "Disponível" com `availableToSpend`, `dailyAllowance`, `remainingDays`
4. Ações por item: Realizar, Pular, Desfazer, Editar
5. `LinkTransactionDialogComponent` em modo realize
6. `BudgetItemFormComponent` em modo edit
7. Templates recorrentes: mostrar inativos + Reativar
8. Botão Sincronizar parcelas

---

## 1. Regenerar Orval

```bash
cd frontend && npm run api:generate
```

**Novos métodos gerados em `BudgetService`:**

| Método | HTTP | Endpoint |
|--------|------|----------|
| `realizeBudgetItem(id, body?)` | POST | `/api/budget-items/{id}/realize` |
| `unrealizeBudgetItem(id)` | DELETE | `/api/budget-items/{id}/realize` |
| `skipBudgetItem(id)` | POST | `/api/budget-items/{id}/skip` |
| `unskipBudgetItem(id)` | DELETE | `/api/budget-items/{id}/skip` |
| `reactivateRecurringBudgetItem(id)` | PATCH | `/api/recurring-budget-items/{id}/reactivate` |

**Novos campos em `BudgetCycleSummary`:**
```ts
availableToSpend?: number;
dailyAllowance?: number;
remainingDays?: number;
```

---

## 2. PlanningService

Adicionar ao `planning.service.ts`:

```ts
realizeItem(id: string, req: BudgetItemRealizeRequest): Observable<BudgetItemResponse>
unrealizeItem(id: string): Observable<BudgetItemResponse>
skipItem(id: string): Observable<BudgetItemResponse>
unskipItem(id: string): Observable<BudgetItemResponse>
reactivateRecurring(id: string): Observable<RecurringBudgetItemResponse>
```

Todos delegam diretamente ao `BudgetService` gerado.

---

## 3. Card "Disponível" (budget-cycle-current)

Novo quarto card no `summary-grid`, exibido **apenas quando `cycle().status === 'OPEN'`**.

Lê direto de `cycle()!.summary` (campo do backend, sem recomputar no cliente):

| Linha | Campo | Label |
|-------|-------|-------|
| Restante para gastar | `summary.availableToSpend` | "Disponível no mês" |
| Por dia | `summary.dailyAllowance` | "Por dia" |
| Dias restantes | `summary.remainingDays` | "Dias restantes" |

Sem lógica de cálculo no frontend — valores vêm do `BudgetCycleSummary` retornado pela API.

**Cor condicional:** `availableToSpend < 0` → texto vermelho (classe CSS `negative`).

---

## 4. Ações por item de planejamento

### 4.1 Matriz de ações (ciclo OPEN)

| Status item | Source | Ações disponíveis |
|-------------|--------|-------------------|
| PENDING | MANUAL | Realizar · Pular · Editar · Vincular (se sem link) · Desvincular (se com link) · Remover |
| PENDING | RECURRING | Realizar · Pular · Vincular (se sem link) · Desvincular (se com link) |
| PENDING | INSTALLMENT | Realizar · Pular · Vincular (se sem link) · Desvincular (se com link) |
| REALIZED | qualquer | Desfazer realizaçao (unrealize) · Desvincular (se com link) |
| SKIPPED | qualquer | Desfazer (unskip) |

Ciclo CLOSED → somente leitura, nenhuma ação.

### 4.2 Novos métodos em `BudgetCycleCurrentComponent`

```ts
realizeItem(item: BudgetItemResponse): void
skipItem(item: BudgetItemResponse): void
unrealizeItem(item: BudgetItemResponse): void
unskipItem(item: BudgetItemResponse): void
editItem(item: BudgetItemResponse): void
syncInstallments(): void
```

**`realizeItem`:** abre `LinkTransactionDialogComponent` em modo `'realize'`. Resultado:
- `undefined` → cancelado, não faz nada
- `null` → chama `planningService.realizeItem(id, {})`
- `string` (transactionId) → chama `planningService.realizeItem(id, { transactionId })`

**`skipItem`:** chama direto `planningService.skipItem(id)`, sem dialog.

**`unrealizeItem`:** chama direto `planningService.unrealizeItem(id)`.

**`unskipItem`:** chama direto `planningService.unskipItem(id)`.

**`editItem`:** abre `BudgetItemFormComponent` em modo `'edit'` com o item. Resultado do dialog é `BudgetItemUpdateRequest`; chama `planningService.updateItem(id, result)`.

**`syncInstallments`:** chama `planningService.syncInstallments(cycleId)`, recarrega o ciclo no retorno.

### 4.3 Ícones dos botões

| Ação | Ícone Material |
|------|---------------|
| Realizar | `check_circle_outline` |
| Pular | `skip_next` |
| Desfazer realização | `undo` |
| Desfazer pulo | `undo` |
| Editar | `edit` |
| Vincular | `link` |
| Desvincular | `link_off` |
| Remover | `delete_outline` |

Tooltips obrigatórios em todos os `mat-icon-button`.

---

## 5. LinkTransactionDialogComponent — modo realize

### Alterações na interface de dados

```ts
export interface LinkTransactionDialogData {
  item: BudgetItemResponse;
  cycleId: string;
  mode?: 'link' | 'realize';  // default: 'link'
}
```

### Comportamento por modo

**Modo `'link'` (sem mudança):**
- Título: "Vincular transação"
- Clique em linha → fecha com `string` (transactionId)
- Cancelar → fecha com `undefined`

**Modo `'realize'` (novo):**
- Título: "Realizar item"
- Subtítulo: "Selecione a transação ou realize sem vincular"
- Botão extra no footer: "Realizar sem vincular" → fecha com `null`
- Clique em linha → fecha com `string` (transactionId)
- Cancelar → fecha com `undefined`

### Tipo de retorno

```ts
// string = transactionId selecionado
// null = realizar sem vincular (só no modo realize)
// undefined = cancelado
dialogRef.close(string | null | undefined)
```

O componente pai trata `null` vs `string` para decidir qual body enviar ao `realizeItem`.

---

## 6. BudgetItemFormComponent — modo edit

### Alterações na interface de dados

```ts
export interface BudgetItemFormData {
  cycleId?: string;
  mode?: 'openCycle' | 'edit';  // adicionar 'edit'
  item?: BudgetItemResponse;    // item para pré-preencher no modo edit
}
```

### Comportamento modo `'edit'`

- Título do dialog: "Editar item"
- Pré-preenche `itemForm` com `data.item.description`, `data.item.amount`, `data.item.expectedDate`
- Campo `type` fica desabilitado (não é alterável via `updateBudgetItem`)
- Submit fecha com `BudgetItemUpdateRequest` (campos: `description`, `amount`, `expectedDate`, `categoryId`, `accountId`)

### Retorno no componente pai

```ts
// No editItem():
ref.afterClosed().subscribe((result?: BudgetItemUpdateRequest) => {
  if (!result) return;
  this.planningService.updateItem(item.id!, result).subscribe({
    next: updated => this.replaceItem(updated),
    ...
  });
});
```

---

## 7. Templates recorrentes — Reativar

### recurring-item-list.ts

- Signal `showInactive = signal(false)`
- Signal `inactiveItems = signal<RecurringBudgetItemResponse[]>([])`
- `ngOnInit`: carrega ativos (comportamento atual) + carrega inativos em paralelo
- Método `reactivate(item)`: chama `planningService.reactivateRecurring(item.id!)`, move item de inativos para ativos

### recurring-item-list.html

- Toggle `MatSlideToggle` "Mostrar inativos" vinculado a `showInactive`
- Itens inativos renderizados com classe `inactive` (opacidade 0.6) quando `showInactive()` é true
- Coluna de ações para inativos: apenas "Reativar" (`play_circle_outline`)
- Ativos mantêm as ações de editar e desativar

---

## 8. Botão Sincronizar Parcelas

No header do ciclo (junto ao botão "Fechar ciclo"), visível apenas quando `status === 'OPEN'`:

```html
<button mat-icon-button matTooltip="Sincronizar parcelas do cartão"
        (click)="syncInstallments()">
  <mat-icon>sync</mat-icon>
</button>
```

Após sucesso: recarrega o ciclo completo via `loadCurrentCycle()`.

---

## Fluxo de dados (summary)

```
GET /api/budget-cycles/current
  └── BudgetCycleResponse
        ├── items[]           → incomeItems / expenseItems / installmentItems (computed)
        └── summary           → cards Receitas, Despesas, Saldo, Disponível
              ├── plannedIncome / realizedIncome
              ├── plannedExpense / realizedExpense
              ├── projectedBalance / currentBalance
              ├── availableToSpend   ← novo card
              ├── dailyAllowance     ← novo card
              └── remainingDays      ← novo card
```

`buildSummary()` em `budget-cycle.utils.ts` continua sendo usado pelos cards existentes. Os três novos campos são lidos diretamente de `cycle()!.summary` (sem recomputar).

---

## Testes

- `budget-cycle.utils.spec.ts`: sem mudança (novos campos vêm do backend)
- `budget-cycle.error-utils.spec.ts`: cobrir mensagem de erro para realize/skip se backend retornar 422
- Vitest: testar `buildSummary` permanece intacto

---

## Arquivos afetados

| Arquivo | Operação |
|---------|----------|
| `frontend/src/app/core/api/` (todo) | Regenerar via Orval |
| `features/planning/planning.service.ts` | Adicionar 5 métodos |
| `features/planning/budget-cycle-current/budget-cycle-current.ts` | Novos métodos de ação |
| `features/planning/budget-cycle-current/budget-cycle-current.html` | 4º card + novos botões de ação |
| `features/planning/budget-cycle-current/budget-cycle-current.scss` | Estilo `.negative` |
| `features/planning/budget-item-form/budget-item-form.ts` | Modo edit |
| `features/planning/budget-item-form/budget-item-form.html` | Campos modo edit |
| `features/planning/link-transaction-dialog/link-transaction-dialog.ts` | Modo realize |
| `features/planning/link-transaction-dialog/link-transaction-dialog.html` | Botão "Realizar sem vincular" |
| `features/planning/recurring-item-list/recurring-item-list.ts` | Toggle + reactivate |
| `features/planning/recurring-item-list/recurring-item-list.html` | Toggle + coluna inativos |
