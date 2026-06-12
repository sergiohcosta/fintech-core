# Planning Shell com Abas Roteadas

**Issue:** #96 — Listagem de planejamento não mostra planejamentos fechados  
**Data:** 2026-06-12  
**Status:** Aprovado

## Problema

Clicar em "Planejamento" no sidenav leva a `/planning/current`, que chama `GET /api/budget-cycles/current`. Esse endpoint retorna apenas ciclos com status `OPEN`. Quando não há ciclo aberto, o usuário cai em um empty state sem nenhum caminho para ver os ciclos fechados. A rota `/planning/cycles` (que lista todos os ciclos) existe, mas não tem link de entrada visível de nenhuma tela.

## Solução

Introduzir um `PlanningShellComponent` que envolve todas as rotas de planejamento e exibe um `mat-tab-nav-bar` com três abas roteadas: **Ciclo atual**, **Histórico** e **Recorrentes**. As abas usam `routerLink`, então a URL muda por aba, o botão Voltar do browser funciona e deep links funcionam.

O fix para o bug é estrutural: com as abas sempre visíveis, o usuário sem ciclo aberto vê o empty state na aba "Ciclo atual" e pode clicar em "Histórico" para acessar ciclos fechados. Não é necessário lógica adicional de "buscar último ciclo fechado".

## Arquitetura de Rotas

### Antes (rotas irmãs / flat)

```
/planning → redirect → /planning/current
/planning/current   → BudgetCycleCurrentComponent
/planning/cycles    → BudgetCycleList
/planning/cycles/:id → BudgetCycleDetail
/planning/recurring → RecurringItemList
```

### Depois (shell pai + filhas)

```
/planning → PlanningShellComponent
  /planning              → redirect → /planning/current
  /planning/current      → BudgetCycleCurrentComponent
  /planning/cycles       → BudgetCycleList
  /planning/cycles/:id   → BudgetCycleDetail
  /planning/recurring    → RecurringItemList
```

O shell renderiza o `mat-tab-nav-bar` no topo e um `<router-outlet>` abaixo. As rotas filhas são lazy-loaded como hoje — a reestruturação não afeta o bundle splitting.

## PlanningShellComponent

Componente enxuto, sem estado nem lógica de negócio.

```ts
@Component({
  selector: 'app-planning-shell',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet, MatTabsModule],
  template: `
    <mat-tab-nav-bar [tabPanel]="panel">
      <a mat-tab-link routerLink="current"   routerLinkActive #c="routerLinkActive"  [active]="c.isActive">Ciclo atual</a>
      <a mat-tab-link routerLink="cycles"    routerLinkActive #h="routerLinkActive"  [active]="h.isActive">Histórico</a>
      <a mat-tab-link routerLink="recurring" routerLinkActive #r="routerLinkActive"  [active]="r.isActive">Recorrentes</a>
    </mat-tab-nav-bar>
    <mat-tab-nav-panel #panel>
      <router-outlet />
    </mat-tab-nav-panel>
  `,
})
export class PlanningShellComponent {}
```

`mat-tab-nav-bar` usa o router nativo do Angular: `routerLinkActive` detecta a rota ativa e `[active]` repassa para o Material para destacar a aba correta. Nenhum signal ou lógica extra é necessário.

## Mudanças nos Componentes Filhos

### BudgetCycleCurrentComponent

Apenas o texto do empty state muda para orientar o usuário sobre a aba Histórico:

```html
<!-- antes -->
<p>Abra um ciclo para começar a planejar o mês.</p>

<!-- depois -->
<p>Abra um ciclo para começar a planejar o mês.<br>
   Ciclos anteriores estão disponíveis na aba <strong>Histórico</strong>.</p>
```

Nenhuma mudança de lógica, signals ou chamadas HTTP.

### BudgetCycleList

Remove o botão `← Ciclo atual` do cabeçalho — o `mat-tab-nav-bar` do shell já cumpre essa função. O título `<h1>Histórico de ciclos</h1>` é mantido dentro do conteúdo da aba para contexto e acessibilidade.

### BudgetCycleDetail, RecurringItemList

Sem alterações.

### Backend

Sem alterações. O `GET /api/budget-cycles` já retorna todos os ciclos independente de status (`findAllByTenantOrderByStartDateDesc` não filtra por status).

## Estados Visuais

### Ciclo aberto (situação normal)

```
[ Ciclo atual* ]  [ Histórico ]  [ Recorrentes ]
──────────────────────────────────────────────────
Planejamento  01/06 – 30/06/2026        [Aberto] [Fechar ciclo]
[ cards de resumo ]
[ accordion de receitas / despesas / parcelas ]
```

### Sem ciclo aberto

```
[ Ciclo atual* ]  [ Histórico ]  [ Recorrentes ]
──────────────────────────────────────────────────
          📅
  Nenhum ciclo aberto
  Abra um ciclo para começar a planejar o mês.
  Ciclos anteriores estão disponíveis na aba Histórico.
          [ Abrir ciclo ]
```

Ao clicar em "Histórico":

```
[ Ciclo atual ]  [ Histórico* ]  [ Recorrentes ]
──────────────────────────────────────────────────
Período              Saldo inicial   Status    Ações
01/05 – 31/05/2026   R$ 5.000       Fechado    👁
01/04 – 30/04/2026   R$ 4.800       Fechado    👁
```

## Escopo Fora desta Issue

- Adicionar badge/contador na aba "Ciclo atual" (ex: itens pendentes) — possível melhoria futura.
- Aba "Recorrentes" com indicação de quantos templates ativos existem — possível melhoria futura.
- Paginação no `BudgetCycleList` — já existe via `page`/`size`, comportamento sem mudança.

## Testes

Não há testes unitários existentes para os componentes de planning. Esta issue não exige novos testes além da verificação manual:

1. Navegar para `/planning` → deve redirecionar para `/planning/current` com a aba "Ciclo atual" ativa.
2. Com ciclo aberto: conteúdo do ciclo aparece normalmente.
3. Sem ciclo aberto: empty state com menção à aba Histórico.
4. Clicar em "Histórico" → URL muda para `/planning/cycles`, aba "Histórico" ativa, lista de ciclos (incluindo fechados) visível.
5. Clicar em "Recorrentes" → URL muda para `/planning/recurring`, aba "Recorrentes" ativa.
6. Acessar `/planning/cycles/:id` diretamente → shell renderiza com aba "Histórico" ativa (routerLinkActive detecta prefixo `cycles`).
7. Botão Voltar do browser: volta para a aba anterior.
