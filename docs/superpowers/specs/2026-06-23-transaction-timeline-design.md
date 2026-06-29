# Transaction Timeline Visual — Design Spec

**Data:** 2026-06-23  
**Status:** Aprovado para implementação  
**Sprint/Contexto:** Feature isolada dentro de `/transactions`

---

## 1. Visão Geral

Nova visualização cronológica das transações do tenant com **3 modos de exibição alternáveis** (tabs), filtros independentes da lista principal, e navegação "Ver lista" que sincroniza filtros para `/transactions`.

**Rota:** `/transactions/timeline` (lazy-loaded, standalone)

---

## 2. Arquitetura de Arquivos

```
frontend/src/app/features/transaction/
├── transaction-timeline/
│   ├── transaction-timeline.ts
│   ├── transaction-timeline.html
│   ├── transaction-timeline.scss
│   ├── transaction-timeline.filters.ts
│   ├── timeline-calendar/
│   │   ├── timeline-calendar.ts
│   │   ├── timeline-calendar.html
│   │   ├── timeline-calendar.scss
│   │   └── calendar-utils.ts
│   ├── timeline-grouped-list/
│   │   ├── timeline-grouped-list.ts
│   │   ├── timeline-grouped-list.html
│   │   └── grouped-list.utils.ts
│   └── timeline-horizontal/
│       ├── timeline-horizontal.ts
│       ├── timeline-horizontal.html
│       ├── timeline-horizontal.scss
│       └── horizontal-utils.ts
```

---

## 3. Roteamento

```typescript
// app.routes.ts
{
  path: 'transactions/timeline',
  loadComponent: () => import('./features/transaction/transaction-timeline/transaction-timeline').then(m => m.TransactionTimelineComponent),
}
```

---

## 4. Filtros (Independentes)

```typescript
// transaction-timeline.filters.ts
export interface TimelineFilters {
  accountIds: string[];
  statuses: TransactionStatus[];
  types: TransactionType[];
  startDate: string | null;
  endDate: string | null;
  description: string | null;
  viewMode: 'calendar' | 'grouped' | 'horizontal';
}
```

- Persistência: `localStorage` key `fintech.timeline.filters`
- Defaults: mês corrente, todas contas/status/tipos
- Toolbar com controles + seletor de `viewMode` + botão "Ver lista"

---

## 5. Fluxo de Dados

```
TransactionTimelineComponent
  ├─ timelineFilters = signal<TimelineFilters>(DEFAULT)
  ├─ transactions = signal<TransactionResponseDTO[]>([])
  ├─ loadTransactions() → TransactionsService.listTransactions(filtros)
  │
  ├─ <mat-tab-group [selectedIndex]="viewModeIndex()">
  │   ├─ Tab 0: <app-timeline-calendar [transactions]="transactions()" />
  │   ├─ Tab 1: <app-timeline-grouped-list [transactions]="transactions()" />
  │   └─ Tab 2: <app-timeline-horizontal [transactions]="transactions()" />
  │
  └─ effect(() => loadTransactions(filters())) com untracked
```

---

## 6. View: Calendário (Heatmap Mensal)

- Grid 6×7 (semanas × dias) do mês filtrado
- Cada célula (dia):
  - Número do dia
  - Badges: verde (receitas), vermelho (despesas), cinza (canceladas)
  - Valor total do dia no rodapé
  - Hover → mini-cards (description, amount, categoryIcon, status)
  - Clique → expande inline lista completa do dia (accordion)
- Navegação mês anterior/próximo atualiza `startDate`/`endDate` → recarrega
- Fundo levemente colorido por intensidade (∝ |valor|)

---

## 7. View: Lista Agrupada por Data

- Ordenação por `effectiveSortDate` (igual lista principal: `invoiceDueDate` para parcelas cartão, `date` demais)
- Grupos relativos: **Hoje / Ontem / Esta semana / Semana passada / Mês anterior / Mais antigos**
- Cada grupo = header colapsível com resumo (total receitas, despesas, saldo, count)
- Cards compactos: ícone categoria, descrição, valor colorido, status chip, badge parcela
- Virtual scroll (`@angular/cdk/scrolling`) se > 200 transações

---

## 8. View: Timeline Horizontal

- Eixo horizontal = tempo (scroll horizontal)
- Marcadores proporcionais à data no range filtrado
- Ponto colorido por tipo (verde/vermelho/cinza) + card flutuante no hover/click
- Stack vertical para transações do mesmo dia
- Zoom slider (semana/mês/trimestre)
- Legenda fixa: tipo, status, parcela

---

## 9. Utilitários Puros (Testáveis no Vitest)

| Arquivo | Funções |
|---------|---------|
| `calendar-utils.ts` | `buildMonthGrid`, `getDayCellData`, `formatMonthLabel` |
| `grouped-list.utils.ts` | `groupByRelativePeriod`, `computeGroupSummary` |
| `horizontal-utils.ts` | `calculateMarkerPosition`, `resolveCollisions` |

---

## 10. Navegação "Ver Lista"

```typescript
goToTransactionList(): void {
  const f = this.filters();
  const params: Record<string, string> = {};
  if (f.accountIds.length) params['accountIds'] = f.accountIds.join(',');
  if (f.statuses.length) params['status'] = f.statuses[0];
  if (f.types.length) params['type'] = f.types[0];
  if (f.startDate) params['startDate'] = f.startDate;
  if (f.endDate) params['endDate'] = f.endDate;
  if (f.description) params['description'] = f.description;
  this.router.navigate(['/transactions'], { queryParams: params });
}
```

**Ajuste necessário no `TransactionList`:** ler `activatedRoute.queryParams` no `ngOnInit` se presentes (hoje só lê `localStorage`).

---

## 11. Testes

- **Unit (Vitest):** Todas as `.utils.ts` — puro TS, sem Angular
- **Component:** `TransactionTimelineComponent` + 3 sub-components (shallow, `TestBed`)
- **Integração:** Fluxo filtros → load → render (Mock `TransactionsService`)

---

## 12. Acessibilidade (a11y)

- `role="grid"` no calendário, `aria-label` nas células
- `aria-expanded` nos grupos colapsíveis
- Focus visível em todos os elementos interativos
- Contraste WCAG AA nas cores de tipo/status
- Navegação por teclado (Tab, Setas, Enter/Space) em todas as views

---

## 13. Performance

- `OnPush` change detection em todos os components
- `trackBy` em `*ngFor` de transações
- Virtual scroll na lista agrupada
- Debounce 300ms no input de descrição
- Lazy-load das 3 views (carregam apenas quando tab ativada)

---

## 14. Dependências Novas

- `@angular/cdk/scrolling` (já disponível via Angular Material)
- Nenhuma dependência externa nova

---

## 15. Critérios de Aceite

1. Acessar `/transactions/timeline` carrega view Calendário por default
2. 3 tabs funcionam e mantêm estado de scroll/expansão ao trocar
3. Filtros persistem no localStorage e sobrevivem a reload
4. "Ver lista" navega para `/transactions` com queryParams corretos
5. Lista principal abre com filtros vindos da timeline aplicados
6. Calendário: navegação mês a mês recarrega dados
7. Lista agrupada: grupos relativos corretos, resumo bate com soma dos itens
8. Horizontal: scroll suave, zoom funciona, cards não sobrepõem
9. Testes unit passam (>90% coverage nas utils)
10. Sem erros de console, sem memory leaks (subscriptions limpas no `ngOnDestroy`)