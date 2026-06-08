# Transaction Filters UX Redesign

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Redesenhar a barra de filtros da listagem de transações para uma experiência mais fluida e visualmente coerente com o restante da interface.

**Escopo adicional:** Renomear o app de "Fintech Core" para "Nosso Dinheirinho" na toolbar do shell.

---

## Contexto

A listagem de transações (`/transactions`) já possui filtros funcionais (accountId, status, type, startDate, endDate) implementados no backend e no frontend. A lógica está correta; o que muda aqui é exclusivamente a **apresentação e interação** no frontend.

Arquivos afetados:

- `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.html` — template do painel (reescrita)
- `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.scss` — estilos (reescrita)
- `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.ts` — lógica do componente (ajustes mínimos)
- `frontend/src/app/features/transaction/transaction-list/transaction-filters/transaction-filters.types.ts` — sem alteração
- `frontend/src/app/features/transaction/transaction-list/transaction-list.html` — chips ativos + estrutura do painel (ajustes)
- `frontend/src/app/features/transaction/transaction-list/transaction-list.scss` — estilos do wrapper e chips ativos (ajustes)
- `frontend/src/app/components/shell/shell.html` — renomear "Fintech Core" → "Nosso Dinheirinho"

---

## Design

### 1. Renome do app na toolbar

`shell.html` linha 5: alterar `Fintech Core` para `Nosso Dinheirinho`.

---

### 2. Estrutura do painel de filtros

O painel continua **colapsável** — abre e fecha via botão "Filtros" no cabeçalho da página. Quando há filtros ativos, o botão exibe um badge numérico com a contagem.

Estrutura interna do painel (de cima para baixo):

```
┌─────────────────────────────────────────────────┐
│  CONTA                                          │
│  [mat-select: Todas as contas ▼]                │
├──────────────────────┬──────────────────────────┤
│  TIPO                │  STATUS                  │
│  [Todos][Despesa]    │  [Todos][Pendente][Pago]  │
│  [Receita]           │  [Cancelado]             │
├──────────────────────┴──────────────────────────┤
│  PERÍODO                                        │
│  [Jan][Fev][Mar][Abr][Mai][Jun*][Jul…][+ Intervalo] │
├─────────────────────────────────────────────────┤
│  ○ Agrupar por período        ✕ Limpar filtros  │
└─────────────────────────────────────────────────┘
```

---

### 3. Campo "Conta"

Mantém `mat-select` com `appearance="outline"`, pois a lista de contas é dinâmica (criada pelo usuário). Label compacta em uppercase, sem `mat-hint` (subscript oculto via `::ng-deep`).

---

### 4. Campos "Tipo" e "Status" — chips coloridos

Substituem os `mat-select` atuais. São exibidos lado a lado em duas colunas.

**Tipo** — chips com cor semântica (idêntica aos badges da tabela):
- `Todos` — neutro (cinza claro quando ativo)
- `Receita` — verde (`#e6f4ea` / `#1e8e3e`)
- `Despesa` — vermelho (`#fce8e6` / `#c5221f`)

**Status** — chips com cor semântica:
- `Todos` — neutro
- `Pendente` — âmbar (`#fef7e0` / `#f29900`)
- `Pago` — verde (`#e6f4ea` / `#1e8e3e`)
- `Cancelado` — cinza (`#f1f3f4` / `#80868b`)

Ao clicar num chip, ele fica "ativo" com borda colorida e fundo semântico. Clicar num segundo chip substitui o anterior (seleção única, como o select anterior). Clicar no chip ativo novamente limpa o filtro (volta para "Todos").

**Implementação:** os sinais `type()` e `status()` do componente já existem. Apenas o template muda — de `mat-select` para `div` clicáveis com `[class.active]` e `(click)`.

---

### 5. Seleção de período — chips de mês

Substitui o navegador `◀ Mês ▶` + dois `input[type=date]`.

**Layout:** linha de chips `Jan | Fev | Mar | Abr | Mai | Jun | Jul | Ago | Set | Out | Nov | Dez | + Intervalo`.

**Comportamento:**
- Chips exibem os meses do ano corrente.
- Meses futuros (depois do mês atual) ficam com `opacity: 0.35` e não são clicáveis.
- Clicar em um mês define `startDate` como o primeiro dia do mês e `endDate` como o último dia (usando `monthBounds()` do `transaction-list.utils.ts`).
- O chip clicado fica ativo (azul, `#e8f0fe` / `#1a73e8`).
- Clicar no chip ativo novamente limpa o filtro de período.
- O chip "+ Intervalo personalizado" (borda tracejada) está sempre visível. Ao clicá-lo, revela dois `input[type=date]` para definir um intervalo livre. Digitar manualmente nas datas também limpa a seleção de chip de mês e exibe "+ Intervalo" como ativo.

**Implementação no componente:**
- Novo método `onMonthChipClick(monthIndex: number)`: calcula `startDate`/`endDate` via `monthBounds` e chama `emit()`.
- Novo método `onCustomIntervalToggle()`: alterna a visibilidade do `custom-interval-inputs`.
- `computed monthChipState()`: retorna array com `{ label, index, active, disabled }` para os 12 meses.
- Os sinais `startDate()` e `endDate()` existentes continuam sendo a fonte da verdade — os chips apenas os preenchem.

---

### 6. Chips de filtros ativos

Permanecem na área entre o painel e a tabela (estrutura existente em `transaction-list.html`). Pequeno refinamento visual:

- Background e cor do chip reflete o tipo de filtro: período = azul, Despesa = vermelho, Receita = verde, Pendente = âmbar, etc.
- Label "Filtros ativos:" em uppercase antes dos chips (quando há ao menos um).
- O `×` de remover individual se mantém.

---

### 7. Rodapé do painel

- Toggle "Agrupar por período" — mantido, movido para o lado esquerdo do footer.
- Botão "✕ Limpar filtros" — lado direito, estilo texto (sem borda).

---

## O que NÃO muda

- Lógica do componente `TransactionFiltersComponent` (`transaction-filters.ts`) além dos novos métodos de mês.
- Interface `TransactionFilters` e `DEFAULT_FILTERS` (`transaction-filters.types.ts`).
- `output<TransactionFilters>` e `input<AccountResponse[]>` do componente.
- Listagem `transaction-list.ts` — apenas pequenos ajustes para o novo estado dos chips ativos.
- Backend — zero mudanças.
- Testes existentes — os novos métodos de chip de mês precisam de cobertura de testes unitários no `.spec.ts` existente.

---

## Testes esperados

No `transaction-list.spec.ts` existente, adicionar cobertura para:

- `onMonthChipClick(5)` → `startDate = '2026-06-01'`, `endDate = '2026-06-30'`
- `onMonthChipClick(5)` chamado duas vezes → limpa o filtro (toggle)
- `monthChipState()` — meses futuros estão desabilitados
- `onCustomIntervalToggle()` — alterna visibilidade do intervalo

---

## Critérios de aceitação

1. Clicar em "Jun" no painel filtra a tabela para junho de 2026 — sem outros cliques.
2. Clicar em "Despesa" exibe só transações de tipo EXPENSE, chip fica vermelho.
3. Clicar no mesmo chip novamente remove o filtro.
4. "+ Intervalo personalizado" revela dois inputs de data; digitar neles funciona como antes.
5. Filtros ativos exibem chips coloridos entre o painel e a tabela.
6. A toolbar exibe "Nosso Dinheirinho".
7. Nenhum teste existente quebra.
