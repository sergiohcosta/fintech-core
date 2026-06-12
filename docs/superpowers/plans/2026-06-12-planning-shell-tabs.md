# Planning Shell com Abas Roteadas — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduzir um `PlanningShellComponent` com `mat-tab-nav-bar` que agrupa as rotas de planejamento em abas roteadas (URL muda por aba), resolvendo o bug #96 onde ciclos fechados ficavam inacessíveis.

**Architecture:** Um novo componente shell envolve as rotas existentes como children. As abas usam `mat-tab-nav-bar` com `routerLink`, de modo que a URL reflete a aba ativa. Os componentes filhos são mantidos intactos — apenas ajustes mínimos de texto e remoção de navegação redundante.

**Tech Stack:** Angular 21 Zoneless, Angular Material 3 (`MatTabsModule`), Angular Router (`RouterLink`, `RouterLinkActive`, `RouterOutlet`).

**Spec:** `docs/superpowers/specs/2026-06-12-planning-shell-tabs-design.md`

---

## Mapa de Arquivos

| Ação | Arquivo |
|------|---------|
| Criar | `frontend/src/app/features/planning/planning-shell/planning-shell.ts` |
| Modificar | `frontend/src/app/features/planning/planning.routes.ts` |
| Modificar | `frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.html` |
| Modificar | `frontend/src/app/features/planning/budget-cycle-list/budget-cycle-list.html` |

Componentes filhos (`BudgetCycleDetail`, `RecurringItemList`) e todo o backend não são alterados.

---

## Task 1: Criar PlanningShellComponent

**Files:**
- Criar: `frontend/src/app/features/planning/planning-shell/planning-shell.ts`

- [ ] **Step 1: Criar o arquivo do componente**

```ts
// frontend/src/app/features/planning/planning-shell/planning-shell.ts
import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatTabsModule } from '@angular/material/tabs';

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

> **Por que `mat-tab-nav-bar` e não `mat-tab-group`?**  
> `mat-tab-group` é visual — o Angular não sabe qual aba está ativa e a URL não muda. `mat-tab-nav-bar` usa `routerLink` reais: cada aba é um link de rota, `routerLinkActive` detecta a rota ativa automaticamente e `[active]` repassa para o Material destacar a aba correta. Back/forward do browser e deep links funcionam sem código adicional.

- [ ] **Step 2: Commit**

```bash
git add frontend/src/app/features/planning/planning-shell/planning-shell.ts
git commit -m "feat(planning): cria PlanningShellComponent com abas roteadas"
```

---

## Task 2: Reestruturar planning.routes.ts

**Files:**
- Modificar: `frontend/src/app/features/planning/planning.routes.ts`

- [ ] **Step 1: Substituir o conteúdo completo do arquivo**

```ts
// frontend/src/app/features/planning/planning.routes.ts
import { Routes } from '@angular/router';

export const planningRoutes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./planning-shell/planning-shell').then(m => m.PlanningShellComponent),
    children: [
      { path: '', redirectTo: 'current', pathMatch: 'full' },
      {
        path: 'current',
        loadComponent: () =>
          import('./budget-cycle-current/budget-cycle-current').then(m => m.BudgetCycleCurrentComponent),
      },
      {
        path: 'cycles',
        loadComponent: () =>
          import('./budget-cycle-list/budget-cycle-list').then(m => m.BudgetCycleList),
      },
      {
        path: 'cycles/:id',
        loadComponent: () =>
          import('./budget-cycle-detail/budget-cycle-detail').then(m => m.BudgetCycleDetail),
      },
      {
        path: 'recurring',
        loadComponent: () =>
          import('./recurring-item-list/recurring-item-list').then(m => m.RecurringItemList),
      },
    ],
  },
];
```

> **Por que o shell é o pai com `path: ''`?**  
> O Angular Router renderiza o componente da rota pai em um `<router-outlet>` da rota avô (o app shell). Os filhos são renderizados no `<router-outlet>` do próprio shell. Isso garante que o `mat-tab-nav-bar` apareça em todas as rotas filhas sem duplicação.

- [ ] **Step 2: Commit**

```bash
git add frontend/src/app/features/planning/planning.routes.ts
git commit -m "feat(planning): reestrutura rotas com PlanningShellComponent como pai"
```

---

## Task 3: Atualizar empty state do BudgetCycleCurrentComponent

**Files:**
- Modificar: `frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.html`

- [ ] **Step 1: Localizar o bloco de empty state (linhas 7–18) e atualizar o parágrafo**

Substituir:
```html
<p>Abra um ciclo para começar a planejar o mês.</p>
```

Por:
```html
<p>Abra um ciclo para começar a planejar o mês.<br>
   Ciclos anteriores estão disponíveis na aba <strong>Histórico</strong>.</p>
```

O restante do arquivo não muda.

- [ ] **Step 2: Commit**

```bash
git add frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.html
git commit -m "fix(planning): empty state menciona aba Histórico para ciclos fechados"
```

---

## Task 4: Limpar BudgetCycleList

**Files:**
- Modificar: `frontend/src/app/features/planning/budget-cycle-list/budget-cycle-list.html`

- [ ] **Step 1: Remover o botão de voltar do cabeçalho**

Substituir o bloco do header (linhas 1–7 do template atual):

```html
<div style="padding:24px;max-width:800px;margin:0 auto">
  <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px">
    <h1 style="margin:0">Histórico de ciclos</h1>
    <button mat-stroked-button routerLink="/planning/current">
      <mat-icon>arrow_back</mat-icon> Ciclo atual
    </button>
  </div>
```

Por:

```html
<div style="padding:24px;max-width:800px;margin:0 auto">
  <div style="margin-bottom:16px">
    <h1 style="margin:0">Histórico de ciclos</h1>
  </div>
```

O `<h1>` é mantido para contexto e acessibilidade. O botão de voltar é removido porque o `mat-tab-nav-bar` do shell já cumpre essa função.

- [ ] **Step 2: Commit**

```bash
git add frontend/src/app/features/planning/budget-cycle-list/budget-cycle-list.html
git add frontend/src/app/features/planning/budget-cycle-list/budget-cycle-list.ts
git commit -m "fix(planning): remove botão de voltar redundante do histórico de ciclos"
```

---

## Task 5: Verificação manual

Rodar o frontend e verificar cada cenário:

```bash
cd frontend && npm start
```

- [ ] **Cenário 1 — Redirecionamento padrão**

Navegar para `http://localhost:4200/planning`.  
Esperado: redireciona para `/planning/current`, aba "Ciclo atual" destacada, `mat-tab-nav-bar` visível.

- [ ] **Cenário 2 — Ciclo aberto**

Com ciclo aberto ativo: conteúdo do ciclo aparece normalmente abaixo das abas. Cards de resumo, accordions de receitas/despesas/parcelas presentes.

- [ ] **Cenário 3 — Sem ciclo aberto**

Fechar o ciclo atual (ou testar sem ciclo aberto).  
Esperado: empty state com texto "Ciclos anteriores estão disponíveis na aba **Histórico**." e botão "Abrir ciclo".

- [ ] **Cenário 4 — Aba Histórico**

Clicar na aba "Histórico".  
Esperado: URL muda para `/planning/cycles`, aba "Histórico" destacada, tabela de ciclos (incluindo fechados) visível. **Este é o bug #96 sendo resolvido.**

- [ ] **Cenário 5 — Detalhe de ciclo fechado**

Clicar no ícone 👁 de um ciclo fechado na listagem.  
Esperado: navega para `/planning/cycles/:id`, aba "Histórico" continua destacada (routerLinkActive detecta prefixo `cycles`).

- [ ] **Cenário 6 — Aba Recorrentes**

Clicar na aba "Recorrentes".  
Esperado: URL muda para `/planning/recurring`, aba "Recorrentes" destacada, lista de itens recorrentes visível.

- [ ] **Cenário 7 — Navegação browser**

Navegar entre abas e pressionar Voltar/Avançar do browser.  
Esperado: navegação funciona normalmente entre as rotas.

- [ ] **Cenário 8 — Deep link**

Acessar diretamente `http://localhost:4200/planning/cycles` no browser.  
Esperado: carrega com aba "Histórico" ativa sem passar por "Ciclo atual".

- [ ] **Commit final se tudo passar**

```bash
git add -p  # verificar se há algo não commitado
git log --oneline -5  # confirmar os 4 commits das tasks anteriores
```
