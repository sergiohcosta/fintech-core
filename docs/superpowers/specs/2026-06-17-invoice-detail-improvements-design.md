# Invoice Detail — Melhorias na Exibição (2026-06-17)

## Contexto

A tela de detalhes de fatura (`/invoices/:id`) exibe breakdown por categoria e tabela de transações. Com faturas com muitas categorias ou parcelas, a tela fica densa e difícil de ler. Duas melhorias foram pedidas:

1. **Breakdown de categorias**: limitar visibilidade ao top 5 por padrão, com botão para expandir as demais.
2. **Seção separada de compras parceladas**: antes das transações avulsas, destacar parcelas com seu subtotal.

---

## Escopo

Alterações exclusivas em **`InvoiceDetail`** (`invoice-detail.ts` + `invoice-detail.html` + `invoice-detail.scss`). Nenhum novo arquivo, nenhum subcomponente, nenhuma mudança de backend.

---

## Feature 1 — Breakdown: Top 5 + "Mostrar mais"

### Comportamento

- Por padrão, a tabela de breakdown exibe apenas as **5 categorias com maior gasto** (já ordenadas por `computeBreakdown`).
- Se houver mais de 5 categorias, aparece abaixo da tabela um botão de texto:
  > "Mostrar mais 3 categorias" *(número dinâmico)*
- Ao clicar, todas as categorias são exibidas. O botão some — **não há botão "Mostrar menos"**.
- Se houver ≤ 5 categorias, nenhum botão é exibido.

### Implementação

```typescript
showAllBreakdown = signal(false);

visibleBreakdown = computed(() =>
  this.showAllBreakdown() ? this.breakdown() : this.breakdown().slice(0, 5)
);

hiddenBreakdownCount = computed(() =>
  Math.max(0, this.breakdown().length - 5)
);
```

Template: `[dataSource]="visibleBreakdown()"`. Abaixo da tabela:

```html
@if (!showAllBreakdown() && hiddenBreakdownCount() > 0) {
  <button mat-button (click)="showAllBreakdown.set(true)">
    Mostrar mais {{ hiddenBreakdownCount() }} categorias
  </button>
}
```

---

## Feature 2 — Seção "Compras Parceladas"

### Posição na página

```
[Cabeçalho]
[Resumo financeiro]
[Breakdown por categoria]   ← já existe
[Compras Parceladas]        ← novo, se installmentTxs().length > 0
[Demais Transações]         ← renomeia "Transações da Fatura"
```

### Comportamento

- A seção aparece apenas se existirem transações com `installmentGroupId != null` na fatura.
- Exibe as mesmas colunas da tabela de transações: `description`, `amount`, `date`, `type`, `status`, `installmentLabel`.
- `installmentLabel` é a coluna mais relevante aqui (ex: "2/12") — mantida em destaque natural por ser a última coluna.
- Um **subtotal** é exibido no título da seção: `Total de parcelas: R$ X.XXX,XX`.
- A seção "Demais Transações" mostra apenas transações sem `installmentGroupId`.
- Se **todas** as transações são parceladas, a seção "Demais Transações" é omitida.

### Implementação

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

Template: duas `<section>` distintas com `@if` guards.

---

## Decisões Técnicas

| Decisão | Motivo |
|---|---|
| Sem botão "Mostrar menos" | Fatura é leitura; o usuário raramente quer recolher depois de expandir |
| Sem extração para utils | Split e slice são one-liners; extração adicionaria indireção sem ganho |
| Sem subcomponentes | Duas tabelas em um componente de detalhe não justificam overhead de DI |
| Subtotal só de ativas | Consistente com `totalExpense` que já exclui `CANCELLED` |

---

## Arquivos Afetados

| Arquivo | Tipo de mudança |
|---|---|
| `invoice-detail.ts` | +4 `computed()` signals, +1 `signal()`, handler simples |
| `invoice-detail.html` | Refactor da section de transações em duas; botão "Mostrar mais" |
| `invoice-detail.scss` | Pequeno ajuste de título/subtotal para a nova seção |

Nenhum arquivo criado. Nenhum arquivo de teste alterado (lógica é trivial demais para testar isoladamente).
