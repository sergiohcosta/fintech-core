# Design: Gerenciamento de Faturas de Cartão de Crédito

**Data:** 2026-06-05  
**Status:** Aprovado

---

## Contexto

O backend de faturas já está completo (entidade `Invoice`, `InvoiceService`, `InvoiceController`, `InvoiceResponseDTO`). O ciclo de vida `OPEN → CLOSED → PAID` está implementado com endpoints `POST /api/invoices/{id}/close` e `POST /api/invoices/{id}/pay`. A listagem de transações por fatura (`GET /api/transactions?invoiceId=`) também já existe.

O que falta é a **UI de gerenciamento de faturas** no frontend.

---

## Roteamento e Pontos de Entrada

### Novas rotas (dentro do shell, protegidas por `authGuard`)

```
/invoices         → InvoiceListComponent   (seletor de conta + lista de faturas)
/invoices/:id     → InvoiceDetailComponent (detalhe da fatura)
```

### Três pontos de entrada

| De onde | Para onde | Mecanismo |
|---|---|---|
| Sidenav "Faturas" | `/invoices` | Novo item no `navItems` do `ShellComponent` (ícone: `receipt_long`) |
| Conta CREDIT_CARD em `/accounts` | `/invoices?accountId=:id` | Botão "Ver Faturas" na linha da conta na `AccountList` |
| Chip de fatura na listagem de transações | `/invoices/:invoiceId` | `[routerLink]` no chip existente no `TransactionList` |

O `InvoiceListComponent` lê `?accountId` via `ActivatedRoute` na inicialização e pré-seleciona a conta automaticamente quando o parâmetro está presente.

---

## `InvoiceListComponent` (`/invoices`)

### Estrutura visual

- Título "Faturas" + `<mat-select>` com as contas CREDIT_CARD do tenant
- Empty state quando nenhuma conta selecionada: mensagem "Selecione um cartão para ver as faturas"
- Tabela de faturas ordenadas da mais recente para a mais antiga (backend já retorna nessa ordem)

### Colunas da tabela

| Coluna | Campo fonte |
|---|---|
| Mês/Ano | `InvoiceResponseDTO.label` (ex: "Junho/2026") |
| Fechamento | `closingDate` |
| Vencimento | `dueDate` |
| Transações | `transactionCount` |
| Total | `totalAmount` (BRL) |
| Status | chip: OPEN=azul, CLOSED=amarelo, PAID=verde |
| Ações | botão "Ver detalhes" → `/invoices/:id` |

### Carregamento de dados

Contas: `AccountsService.listAccounts()` — filtra no frontend por `type === 'CREDIT_CARD'`.  
Faturas: `InvoicesService.listInvoices({ accountId })` — chamado via `effect()` quando `selectedId` muda.

### Signals

```ts
accounts   = signal<AccountResponse[]>([]);
selectedId = signal<string | null>(null);
invoices   = signal<InvoiceResponseDTO[]>([]);
loading    = signal(false);
```

---

## `InvoiceDetailComponent` (`/invoices/:id`)

### Layout em três blocos verticais

**Bloco 1 — Cabeçalho**
- Nome da conta, mês/ano da fatura, datas de fechamento e vencimento
- Chip de status (mesmo estilo da lista)
- Botão "Fechar Fatura": visível apenas quando `status === 'OPEN'`
- Botão "Pagar Fatura": visível apenas quando `status === 'CLOSED'`
- Ambos abrem `ConfirmationDialogComponent` antes de chamar o backend; atualizam o signal de fatura com o retorno

**Bloco 2 — Resumo financeiro + Breakdown por categoria**
- Três cards lado a lado: **Receitas** (soma `INCOME`), **Despesas** (soma `EXPENSE`), **Saldo líquido** (receitas − despesas)
- Tabela de breakdown por categoria:
  - Inclui todas as transações ativas (INCOME e EXPENSE — cashbacks e reembolsos também aparecem)
  - Colunas: Categoria, Nº transações, Total líquido (BRL), % do total de despesas
  - Transações sem categoria agrupadas como "Sem categoria"
  - Ordenado por valor absoluto total decrescente
  - Transações `CANCELLED` excluídas do cálculo

**Bloco 3 — Transações da fatura**
- Tabela read-only: Descrição, Valor, Data, Tipo, Status, Parcela (quando aplicável)
- Exibe todas as transações da fatura, incluindo CANCELLED (registro histórico completo)
- Sem botões de editar/excluir (contexto de visualização)
- Chip de fatura omitido (redundante dentro da tela de fatura)
- Carregado via `GET /api/transactions?invoiceId=:id`

### Signals e computeds

```ts
invoice      = signal<InvoiceResponseDTO | null>(null);
transactions = signal<TransactionResponseDTO[]>([]);

// Exclui CANCELLED de todos os cálculos
activeTransactions = computed(() =>
  transactions().filter(t => t.status !== 'CANCELLED')
);

totalIncome  = computed(() =>
  activeTransactions()
    .filter(t => t.type === 'INCOME')
    .reduce((sum, t) => sum + t.amount, 0)
);

totalExpense = computed(() =>
  activeTransactions()
    .filter(t => t.type === 'EXPENSE')
    .reduce((sum, t) => sum + t.amount, 0)
);

netBalance = computed(() => totalIncome() - totalExpense());

breakdown = computed(() => {
  // Agrupa activeTransactions por categoryName (null → "Sem categoria")
  // Soma amount por grupo, calcula % em relação a totalExpense
  // Retorna ordenado por total decrescente
});
```

---

## Mudanças no Backend

Nenhum novo endpoint. Dois ajustes menores durante a implementação:

1. **Cleanup opcional:** `InvoiceController.toDTO()` injeta `AccountRepository` e `TransactionRepository` diretamente no controller — considerar mover a lógica de totalização para o `InvoiceService` para melhor separação de responsabilidades.
2. **OpenAPI spec:** declarar `routerLink` no chip de fatura é mudança puramente de frontend; a spec do backend não precisa mudar.

---

## Mudanças no Frontend (resumo de arquivos)

| Arquivo | Tipo de mudança |
|---|---|
| `app.routes.ts` | Adicionar rotas `/invoices` e `/invoices/:id` |
| `shell.ts` | Adicionar item "Faturas" ao `navItems` |
| `account-list.html` + `account-list.ts` | Adicionar botão "Ver Faturas" para contas CREDIT_CARD |
| `transaction-list.html` | Adicionar `[routerLink]` no chip de fatura |
| `features/invoice/invoice-list/` | Novo componente (`.ts`, `.html`, `.scss`, `.spec.ts`) |
| `features/invoice/invoice-detail/` | Novo componente (`.ts`, `.html`, `.scss`, `.spec.ts`) |

---

## Testes Frontend (Vitest)

### `InvoiceListComponent`
- Pré-seleciona conta quando `?accountId` está presente na URL
- Exibe empty state quando nenhuma conta selecionada
- Renderiza faturas corretamente após seleção
- Chip de status renderiza classe correta para OPEN/CLOSED/PAID

### `InvoiceDetailComponent`
- `totalIncome` soma apenas transações INCOME não canceladas
- `totalExpense` soma apenas transações EXPENSE não canceladas
- `netBalance` = receitas − despesas
- `breakdown` agrupa corretamente, agrupa nulos como "Sem categoria", ordena por total decrescente
- Botão "Fechar" visível apenas para status OPEN
- Botão "Pagar" visível apenas para status CLOSED
- Confirmação exibida antes de fechar/pagar

---

## Decisões e Justificativas

**Por que breakdown no frontend, não no backend?**  
Os dados já vêm nas transações (`categoryName`, `amount`, `type`). Criar um endpoint específico adicionaria superfície de API sem benefício — o volume de transações por fatura não justifica a otimização de tráfego.

**Por que tabela read-only no detalhe, não reutilizar `TransactionList`?**  
O `TransactionList` tem lógica de exclusão, edição, parcelamento e transferência que não faz sentido no contexto de uma fatura. Reutilizar exigiria muitos `@Input` booleanos de controle — mais complexidade do que criar um componente leve focado.

**Por que `effect()` para recarregar faturas ao trocar conta?**  
O projeto usa Angular Zoneless com Signals. `effect()` é o mecanismo correto para reagir a mudanças de signal e disparar efeitos colaterais (chamadas HTTP) — equivalente a `ngOnChanges` mas para o modelo reativo de Signals.
