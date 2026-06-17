# Invoice Detail — Melhorias na Exibição — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Limitar o breakdown de categorias ao top 5 com botão "Mostrar mais" e adicionar seção separada de compras parceladas acima das demais transações.

**Architecture:** Dois `computed()` signals adicionais para o breakdown (`visibleBreakdown`, `hiddenBreakdownCount`, `showAllBreakdown`) e três para a divisão de transações (`installmentTxs`, `regularTxs`, `installmentSubtotal`). O template é refatorado para usar esses signals. Zero novos arquivos, zero backend.

**Tech Stack:** Angular 21 Zoneless, Angular Material 3, Vitest

## Global Constraints

- Angular Zoneless: nunca usar `markForCheck()` ou APIs Zone; signals e `computed()` são suficientes
- TypeScript estrito: sem `any`
- Commits em português, sem `Co-Authored-By`
- Rodar `npm test` a partir de `frontend/` (Vitest)

---

## Arquivos Afetados

| Arquivo | Mudança |
|---|---|
| `frontend/src/app/features/invoice/invoice-detail/invoice-detail.ts` | +3 signals/computeds (Task 1) e +3 (Task 2) |
| `frontend/src/app/features/invoice/invoice-detail/invoice-detail.html` | Breakdown usa `visibleBreakdown()` + botão (Task 1); split da section de transações em duas (Task 2) |
| `frontend/src/app/features/invoice/invoice-detail/invoice-detail.scss` | `.show-more-container` (Task 1) + `.section-heading`/`.section-subtitle` (Task 2) |

---

### Task 1: Breakdown — Top 5 + botão "Mostrar mais"

**Files:**
- Modify: `frontend/src/app/features/invoice/invoice-detail/invoice-detail.ts`
- Modify: `frontend/src/app/features/invoice/invoice-detail/invoice-detail.html`
- Modify: `frontend/src/app/features/invoice/invoice-detail/invoice-detail.scss`

**Interfaces:**
- Produz: `showAllBreakdown: WritableSignal<boolean>`, `visibleBreakdown: Signal<CategoryBreakdownRow[]>`, `hiddenBreakdownCount: Signal<number>` — usados apenas no template desta task

- [ ] **Step 1: Adicionar os três signals em `invoice-detail.ts`**

Localizar o bloco de `computed()` existente (linha ~61) e inserir logo após o `breakdown`:

```typescript
// em invoice-detail.ts, após a linha:
// breakdown = computed<CategoryBreakdownRow[]>(() => ...);

showAllBreakdown = signal(false);

visibleBreakdown = computed<CategoryBreakdownRow[]>(() =>
  this.showAllBreakdown() ? this.breakdown() : this.breakdown().slice(0, 5)
);

hiddenBreakdownCount = computed(() => Math.max(0, this.breakdown().length - 5));
```

- [ ] **Step 2: Atualizar `[dataSource]` do breakdown no template**

Em `invoice-detail.html`, localizar:

```html
<table mat-table [dataSource]="breakdown()">
```

Substituir por:

```html
<table mat-table [dataSource]="visibleBreakdown()">
```

- [ ] **Step 3: Adicionar botão "Mostrar mais" após o `</table>` do breakdown**

Logo antes do `</div>` que fecha `.breakdown-container`, inserir:

```html
            @if (!showAllBreakdown() && hiddenBreakdownCount() > 0) {
              <div class="show-more-container">
                <button mat-button (click)="showAllBreakdown.set(true)">
                  Mostrar mais {{ hiddenBreakdownCount() }} {{ hiddenBreakdownCount() === 1 ? 'categoria' : 'categorias' }}
                </button>
              </div>
            }
```

O bloco completo do `breakdown-container` ficará assim:

```html
      @if (breakdown().length > 0) {
        <div class="breakdown-container mat-elevation-z1">
          <h3 class="section-title">Breakdown por Categoria</h3>
          <table mat-table [dataSource]="visibleBreakdown()">
            <ng-container matColumnDef="categoryName">
              <th mat-header-cell *matHeaderCellDef>Categoria</th>
              <td mat-cell *matCellDef="let row">
                <span class="category-cell">
                  @if (row.categoryIcon) {
                    <mat-icon class="category-icon">{{ row.categoryIcon }}</mat-icon>
                  }
                  {{ row.categoryPath }}
                </span>
              </td>
            </ng-container>
            <ng-container matColumnDef="count">
              <th mat-header-cell *matHeaderCellDef>Transações</th>
              <td mat-cell *matCellDef="let row">{{ row.count }}</td>
            </ng-container>
            <ng-container matColumnDef="total">
              <th mat-header-cell *matHeaderCellDef>Total</th>
              <td mat-cell *matCellDef="let row">
                {{ row.total | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}
              </td>
            </ng-container>
            <ng-container matColumnDef="percentage">
              <th mat-header-cell *matHeaderCellDef>% Despesas</th>
              <td mat-cell *matCellDef="let row">{{ row.percentage | number:'1.1-1' }}%</td>
            </ng-container>
            <tr mat-header-row *matHeaderRowDef="breakdownColumns"></tr>
            <tr mat-row *matRowDef="let row; columns: breakdownColumns;"></tr>
          </table>
          @if (!showAllBreakdown() && hiddenBreakdownCount() > 0) {
            <div class="show-more-container">
              <button mat-button (click)="showAllBreakdown.set(true)">
                Mostrar mais {{ hiddenBreakdownCount() }} {{ hiddenBreakdownCount() === 1 ? 'categoria' : 'categorias' }}
              </button>
            </div>
          }
        </div>
      }
```

- [ ] **Step 4: Adicionar estilo `.show-more-container` no SCSS**

Ao final de `invoice-detail.scss`:

```scss
.show-more-container {
  padding: 4px 16px 8px;
  display: flex;
  justify-content: center;
}
```

- [ ] **Step 5: Rodar testes existentes**

```bash
cd frontend && npm test -- --run
```

Esperado: todos os testes passando (nenhum teste existente deve ser afetado).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/features/invoice/invoice-detail/invoice-detail.ts \
        frontend/src/app/features/invoice/invoice-detail/invoice-detail.html \
        frontend/src/app/features/invoice/invoice-detail/invoice-detail.scss
git commit -m "feat: limita breakdown de categorias ao top 5 com botão mostrar mais"
```

---

### Task 2: Seção separada de Compras Parceladas

**Files:**
- Modify: `frontend/src/app/features/invoice/invoice-detail/invoice-detail.ts`
- Modify: `frontend/src/app/features/invoice/invoice-detail/invoice-detail.html`
- Modify: `frontend/src/app/features/invoice/invoice-detail/invoice-detail.scss`

**Interfaces:**
- Produz: `installmentTxs: Signal<TransactionResponseDTO[]>`, `regularTxs: Signal<TransactionResponseDTO[]>`, `installmentSubtotal: Signal<number>` — usados apenas no template desta task

- [ ] **Step 1: Adicionar os três computeds em `invoice-detail.ts`**

Após `hiddenBreakdownCount` (adicionado na Task 1), inserir:

```typescript
installmentTxs = computed(() =>
  this.transactions().filter(t => t.installmentGroupId != null)
);

regularTxs = computed(() =>
  this.transactions().filter(t => t.installmentGroupId == null)
);

installmentSubtotal = computed(() =>
  this.installmentTxs()
    .filter(t => t.status !== TransactionStatus.CANCELLED)
    .reduce((sum, t) => sum + t.amount, 0)
);
```

- [ ] **Step 2: Substituir o bloco de transações no template**

Em `invoice-detail.html`, localizar e remover o bloco inteiro:

```html
    <!-- Bloco 3: Transações da fatura (read-only, inclui CANCELLED) -->
    <section class="transactions-section">
      ...
    </section>
```

Substituir pelo par de seções abaixo. **Atenção:** as column defs precisam ser repetidas em cada `mat-table` — é o comportamento padrão do Angular Material.

```html
    <!-- Bloco 3: Compras Parceladas -->
    @if (installmentTxs().length > 0) {
      <section class="transactions-section">
        <div class="section-heading">
          <h3 class="section-title">Compras Parceladas</h3>
          <span class="section-subtitle">
            Total nesta fatura: {{ installmentSubtotal() | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}
          </span>
        </div>
        <div class="table-container mat-elevation-z1">
          <table mat-table [dataSource]="installmentTxs()">
            <ng-container matColumnDef="description">
              <th mat-header-cell *matHeaderCellDef>Descrição</th>
              <td mat-cell *matCellDef="let row">{{ row.description }}</td>
            </ng-container>
            <ng-container matColumnDef="amount">
              <th mat-header-cell *matHeaderCellDef>Valor</th>
              <td mat-cell *matCellDef="let row">
                <span [class]="'amount ' + row.type.toLowerCase()">
                  {{ row.amount | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}
                </span>
              </td>
            </ng-container>
            <ng-container matColumnDef="date">
              <th mat-header-cell *matHeaderCellDef>Data</th>
              <td mat-cell *matCellDef="let row">{{ row.date | date:'dd/MM/yyyy' }}</td>
            </ng-container>
            <ng-container matColumnDef="type">
              <th mat-header-cell *matHeaderCellDef>Tipo</th>
              <td mat-cell *matCellDef="let row">
                <span [class]="'type-badge type-' + row.type.toLowerCase()">
                  {{ typeLabel(row.type) }}
                </span>
              </td>
            </ng-container>
            <ng-container matColumnDef="status">
              <th mat-header-cell *matHeaderCellDef>Status</th>
              <td mat-cell *matCellDef="let row">
                <span [class]="'status-badge status-' + row.status.toLowerCase()">
                  {{ transactionStatusLabel(row.status) }}
                </span>
              </td>
            </ng-container>
            <ng-container matColumnDef="installmentLabel">
              <th mat-header-cell *matHeaderCellDef>Parcela</th>
              <td mat-cell *matCellDef="let row">{{ row.installmentLabel ?? '—' }}</td>
            </ng-container>
            <tr mat-header-row *matHeaderRowDef="transactionColumns"></tr>
            <tr mat-row *matRowDef="let row; columns: transactionColumns;"></tr>
          </table>
        </div>
      </section>
    }

    <!-- Bloco 4: Demais Transações (oculto quando todas são parceladas; exibe empty state quando fatura vazia) -->
    @if (regularTxs().length > 0 || installmentTxs().length === 0) {
      <section class="transactions-section">
        <h3 class="section-title">
          {{ installmentTxs().length > 0 ? 'Demais Transações' : 'Transações da Fatura' }}
        </h3>
        <div class="table-container mat-elevation-z1">
          <table mat-table [dataSource]="regularTxs()">
            <ng-container matColumnDef="description">
              <th mat-header-cell *matHeaderCellDef>Descrição</th>
              <td mat-cell *matCellDef="let row">{{ row.description }}</td>
            </ng-container>
            <ng-container matColumnDef="amount">
              <th mat-header-cell *matHeaderCellDef>Valor</th>
              <td mat-cell *matCellDef="let row">
                <span [class]="'amount ' + row.type.toLowerCase()">
                  {{ row.amount | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}
                </span>
              </td>
            </ng-container>
            <ng-container matColumnDef="date">
              <th mat-header-cell *matHeaderCellDef>Data</th>
              <td mat-cell *matCellDef="let row">{{ row.date | date:'dd/MM/yyyy' }}</td>
            </ng-container>
            <ng-container matColumnDef="type">
              <th mat-header-cell *matHeaderCellDef>Tipo</th>
              <td mat-cell *matCellDef="let row">
                <span [class]="'type-badge type-' + row.type.toLowerCase()">
                  {{ typeLabel(row.type) }}
                </span>
              </td>
            </ng-container>
            <ng-container matColumnDef="status">
              <th mat-header-cell *matHeaderCellDef>Status</th>
              <td mat-cell *matCellDef="let row">
                <span [class]="'status-badge status-' + row.status.toLowerCase()">
                  {{ transactionStatusLabel(row.status) }}
                </span>
              </td>
            </ng-container>
            <ng-container matColumnDef="installmentLabel">
              <th mat-header-cell *matHeaderCellDef>Parcela</th>
              <td mat-cell *matCellDef="let row">{{ row.installmentLabel ?? '—' }}</td>
            </ng-container>
            <tr mat-header-row *matHeaderRowDef="transactionColumns"></tr>
            <tr mat-row *matRowDef="let row; columns: transactionColumns;"></tr>
            <tr class="mat-row" *matNoDataRow>
              <td class="mat-cell empty-state" [attr.colspan]="transactionColumns.length">
                <p>Nenhuma transação nesta fatura.</p>
              </td>
            </tr>
          </table>
        </div>
      </section>
    }
```

- [ ] **Step 3: Adicionar estilos no SCSS**

Ao final de `invoice-detail.scss` (após `.show-more-container` adicionado na Task 1):

```scss
.section-heading {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 16px;

  .section-title { margin-bottom: 0; }
}

.section-subtitle {
  font-size: 13px;
  color: #666;
}
```

- [ ] **Step 4: Rodar testes existentes**

```bash
cd frontend && npm test -- --run
```

Esperado: todos os testes passando. Os testes de `invoice-detail.spec.ts` já cobrem `totalIncome`/`totalExpense`/`netBalance` e botões de ação — continuam válidos pois os computeds existentes não foram alterados.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/invoice/invoice-detail/invoice-detail.ts \
        frontend/src/app/features/invoice/invoice-detail/invoice-detail.html \
        frontend/src/app/features/invoice/invoice-detail/invoice-detail.scss
git commit -m "feat: adiciona seção separada de compras parceladas na tela de detalhes da fatura"
```
