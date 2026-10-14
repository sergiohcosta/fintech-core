# Design: Planejamento mobile (Fase 6 — mobile-friendly)

**Data:** 2026-10-14
**Escopo:** Frontend only — adaptar `budget-cycle-current` e `breakdown` para mobile-first
**Fase do roadmap:** Slice 6 de 7

---

## Contexto e motivação

"Conferir saldo disponível do planejamento" é a 3ª tarefa mobile mais comum (per grill Q2). Hoje:

- `budget-cycle-current` é uma página com 2 cards de summary e tabela de breakdown, layout desktop-centric
- `breakdown` é componente irmão com detalhes por categoria — em desktop expande inline
- `planning-shell` tem `mat-tab-nav-bar` com 4 abas (current, cycles, recurring, settings) — funciona em desktop mas em 375px fica apertado e exige scroll horizontal pra algumas tabs

Esse design entrega os cards de resumo (`availableToSpend` / `dailyAllowance`) full-width, com breakdown em detail page em compact (em vez de inline), e mantém as tabs como `mat-tab-nav-bar` mas mais legíveis em touch.

---

## Decisões já tomadas (resumo do grill pré-execução)

| # | Decisão | Origem |
|---|---|---|
| Layout mobile-first | Cards full-width em compact, 2 colunas em medium, 4 colunas em expanded | padrão do projeto |
| Breakdown | Em compact vai pra **detail page** (rota `/planning/breakdown`); em expanded continua inline (toggle) | decisão de UX |
| Tabs | Manter `mat-tab-nav-bar` mas com labels mais curtos em compact ("Atual" / "Ciclos" / "Recorrentes" / "Ajustes") | ergonometria |
| Tokens | `--space-*`, `--touch-target-min`, mixin `bp.compact` (slice 1) | padrão |
| Sem libs novas | — | padrão |

---

## Mudanças concretas

### 1. `budget-cycle-current.scss` — layout responsivo

```scss
// Default desktop (atual):
.summary-cards { grid-template-columns: repeat(2, 1fr); }

@include bp.compact {
  .summary-cards {
    grid-template-columns: 1fr;        // cards full-width em mobile
    gap: var(--space-md);
  }
  .available-to-spend {
    padding: var(--space-lg);
    font-size: 1.5em;                 // valor em destaque
  }
  .daily-allowance {
    padding: var(--space-md);
    font-size: 1em;
  }
}
```

**Card `availableToSpend`**: maior, em destaque, valor formatado grande (currency pipe).
**Card `dailyAllowance`**: menor, secundário, com label "Por dia até o fim do mês".

### 2. Breakdown — dual mode

**`budget-cycle-breakdown.ts`** ganha `BreakpointObserver` injection + signal `isCompact`:

- Em **compact**: tap em qualquer item do breakdown → navega para `/planning/breakdown/:categoryId` (rota nova ou detail expandida). Já existe rota `/planning/breakdown`? Verificar em `planning.routes.ts`.
- Em **expanded**: comportamento atual (expand inline via toggle).

### 3. `planning-shell` — tabs compactas

```html
<nav mat-tab-nav-bar [tabPanel]="tabPanel" class="planning-tabs">
  <a mat-tab-link
     [routerLink]="['/planning', 'current']"
     routerLinkActive #rla="routerLinkActive"
     [active]="rla.isActive">
    @if (isCompact()) { Atual } @else { Ciclo Atual }
  </a>
  <!-- repetir para cycles, recurring, settings -->
</nav>
```

Labels mais curtos em compact. Garante ≤24 caracteres por tab em 375px.

### 4. Year/month selector em compact

Se houver picker de mês/ano no header, em compact vira chip button (`<mat-chip>` ou `<button mat-stroked-button>`) com `min-height: var(--touch-target-min)`. Em expanded mantém dropdown atual.

---

## Arquivos a criar/alterar

| Arquivo | Tipo | Mudança |
|---|---|---|
| `frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.scss` | edit | Cards responsivos (compact 1col / desktop 2col) |
| `frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.ts` | edit (mínimo) | Só se precisar `isCompact` no template |
| `frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.html` | edit | Classes/estrutura para novo grid |
| `frontend/src/app/features/planning/breakdown/budget-cycle-breakdown.ts` | edit | `BreakpointObserver`, signal `isCompact` |
| `frontend/src/app/features/planning/breakdown/budget-cycle-breakdown.html` | edit | `@if (isCompact())` para detail-page pattern |
| `frontend/src/app/features/planning/planning-shell/planning-shell.ts` | edit | `BreakpointObserver`, signal `isCompact` |
| `frontend/src/app/features/planning/planning-shell/planning-shell.html` | edit | Tab labels condicionais |

Total: 7 edits. Sem novos componentes (a detail page do breakdown já deve existir como rota; caso contrário, criar é trivial).

---

## Critérios de aceite

### Funcionais

- [ ] Em compact: `availableToSpend` em card full-width, valor grande
- [ ] Em compact: `dailyAllowance` em card full-width, valor menor
- [ ] Em medium (720-1023px): 2 colunas
- [ ] Em expanded (≥1024px): layout atual preservado
- [ ] Tabs do planning-shell: labels curtos em compact, completos em expanded
- [ ] Breakdown: tap em item navega para detail em compact; expand inline em expanded
- [ ] Sem regressão em medium/expanded (comparar antes/depois)

### Não-funcionais

- [ ] Build verde (`npm run build`)
- [ ] 373+ testes Vitest verde
- [ ] Sem `<any>` TypeScript
- [ ] Sem `console.log`
- [ ] Sem `Co-Authored-By`
- [ ] Tokens `--space-*` e `--touch-target-min` aplicados em planning
- [ ] Mixin `bp.compact` aplicado
- [ ] Comentário pedagógico no topo dos TS novos/editados

### Validação manual

- [ ] Compact (375px): cards full-width, breakdown detail navegável, tabs legíveis
- [ ] Expanded (≥1024px): 2 colunas, breakdown inline, tabs com labels completos

---

## Riscos abertos

1. **Rota de breakdown detail em compact**: pode não existir rota `/planning/breakdown/:id`. Verificar `planning.routes.ts` antes de codar; se não existir, criar.
2. **Year/month selector** no header: pode estar em `planning-shell` ou `budget-cycle-current`. Verificar onde mora.
3. **Cálculo de `dailyAllowance`**: se mudar a forma de cálculo, é regressão. NÃO mexer na lógica, só no display.

---

## Out of scope (próxima fase)

| Fase | Tema |
|---|---|
| **7** | Storage + biometria (IndexedDB + WebAuthn) — independente desta |

---

## Notas de implementação pro @designer

- Caminho da worktree: `/home/sergio/projetos/fintech-core/.worktrees/planning-mobile`. Use `git -C ...` em todos os comandos git.
- Você tem contexto denso de `transactions.service`, `categories.service`, `accounts.service` (do trabalho da Fase 4) — mas para `planning` precisará ler `frontend/src/app/features/planning/planning.routes.ts`, `learning-cycle-current.ts` e `breakdown.ts` (paths novos, ler!).
- 1 commit só: `feat(planning): adapta budget-cycle-current e breakdown para mobile-first`
- Mensagem PT-BR imperativa, sem `Co-Authored-By`
- Build verde (`npm run build`) + 373+ testes Vitest verde (`npm test -- --watch=false`, NUNCA `npx vitest` direto)