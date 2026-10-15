# Design: Mobile-friendly shell (slice vertical fino)

**Data:** 2026-10-14
**Escopo:** Frontend only — design tokens base, shell responsivo, dashboard mobile-first
**Fase do roadmap:** Slice 1 de 7 (ver "Roadmap de fases" no final)
**Issues:** este design precede a criação de issues dedicadas por fase

---

## Contexto e motivação

A interface web do fintech-core foi construída **desktop-first**:

- `<mat-sidenav mode="side">` fixo em 220px, sem breakpoint para `over`
- Lista de transações é `<mat-table>` de 10+ colunas, sem virtual scroll nem swipe
- Zero design tokens centralizados — `breakpoint`, `spacing` e `typography` espalhados em `@media` ad-hoc (600/640/768px misturados em 8+ arquivos)
- Zero infraestrutura PWA (sem service worker, sem manifest)
- `<mat-toolbar>` no topo + drawer no lado ignoram que o polegar mobile não chega na faixa superior

Resultado: usuário mobile abre o app no celular, encontra layout apertado, elementos abaixo do mínimo de 48dp para toque, e UX que ignora padrões mobile (bottom sheet, swipe, tab bar polegar-acessível).

Esse design documenta o **primeiro slice vertical fino** de uma jornada maior. Entrega o **mínimo mobile-friendly usável**: navegação adaptada, base de tokens reutilizável, e a tela mais vista (dashboard) legível em smartphone. Fases seguintes vêm em issues dedicadas.

**Por que esse slice primeiro**: navegação + tokens base desbloqueiam todas as fases seguintes. Sem isso, qualquer trabalho mobile vira remendo ad-hoc.

---

## Decisões de design (resumo do grill pré-execução)

### Escopo

- **Dispositivos**: smartphones em portrait; tablets compartilham o mesmo layout responsivo (sem código dedicado)
- **Mobile-first tasks** (4): dashboard, lançar transação rápida, conferir saldo disponível do planejamento, conferir transações recentes
- **Stack**: padrões do projeto — Angular standalone + Material 3 + Signals + SCSS, sem libs novas

### Capacidades mobile-native (nesta fase)

- **Service Worker** (shell + leituras `staleWhileRevalidate`) — fase dedicada
- **Câmera** para foto de comprovante — fase dedicada (integração final via bottom sheet de transação)
- **Biometria** no login — fase dedicada (pragmática: JWT em IndexedDB + `CredentialsContainer.get`)
- **PWA instalável (a)** — **não** nesta jornada (NUNCA confirmado como prioridade)

### Padrões de interação

- **Navegação**: bottom tab bar (4 destinos mobile-first) + drawer híbrido para o resto (admin, equipe, categorias, contas)
- **Lista**: cards full-width virtualizados com swipe actions (editar/excluir); `<table>` original fica no desktop
- **Touch**: long press para menu contextual, bottom sheet em vez de popover, `@media (hover: hover)` como divisor desktop-only
- **Lançar transação**: bottom sheet modal (não rota full-screen)
- **Service Worker**: app shell + leituras stale-while-revalidate; **sem** fila de escritas offline

### Tokens

- **Breakpoints**: 3 zonas — compact `<720px`, medium `720–1023px`, expanded `≥1024px`
- **Touch target mínimo**: 48dp (cobre Apple HIG 44pt e Material 48dp)
- **Spacing**: tokens semânticos reaproveitáveis (`--space-xs/sm/md/lg/xl/2xl`), reescalados por breakpoint via `clamp()` quando útil

---

## Achados do `@explorer` que afetam o slice

1. **Zero infra PWA**: sem `ngsw-config.json`, sem `manifest.webmanifest`, sem `@angular/pwa` instalado. Trabalho parte do zero na fase dedicada.
2. **Zero design tokens**: não existe `_tokens.scss`, `_breakpoints.scss`, `_variables.scss` no projeto. Responsivo atual é `@media` ad-hoc misturado. **Fase 0 é pré-requisito obrigatório** das Fases 1+2.
3. **`<mat-sidenav mode="side">` fixo em 220px** — não colapsa. É o ponto de entrada mais impactante da navegação.
4. **JWT em `localStorage`** com literal `'auth_token'` **duplicado** em `frontend/src/app/core/interceptors/auth.interceptor.ts:4` (fora do `AuthService`). Pré-requisito da biometria (não neste slice).
5. **Testes são Vitest + jsdom** — não simulam viewport real. Validação visual vai por **Chrome DevTools device mode + ADB + Chrome `chrome://inspect`** em Android real, e **Playwright** (já instalado) pra E2E mobile. Unit tests cobrem lógica, não layout.
6. **`android/` na raiz** é protótipo Kotlin/Compose legado — **fora de escopo**, ignorado.

---

## Mudanças concretas

### Fase 0 — Design tokens base

**Novos arquivos:**

- `frontend/src/styles/_tokens.scss` — CSS custom properties para:
  - `--space-xs: 4px`, `--space-sm: 8px`, `--space-md: 16px`, `--space-lg: 24px`, `--space-xl: 32px`, `--space-2xl: 48px`
  - `--touch-target-min: 48px`
  - (sem typography/cores próprias nesta fase — Material default basta)
- `frontend/src/styles/_breakpoints.scss` — SCSS mixins:
  - `@mixin compact { @media (max-width: 719.98px) { @content; } }`
  - `@mixin medium { @media (min-width: 720px) and (max-width: 1023.98px) { @content; } }`
  - `@mixin expanded { @media (min-width: 1024px) { @content; } }`

**Edit:**

- `frontend/src/styles.scss` — adicionar `@use './styles/breakpoints' as bp;` no topo, importar tokens via `@use './styles/tokens';` antes do `mat.theme()`.

**Restrições:**

- Não duplicar tokens de Material já existentes (`--mat-sys-*`) — só complementar
- Comentário pedagógico no topo de cada arquivo explicando "por que" (regra do projeto: mentor sênior)

### Fase 1 — Shell responsivo (navegação mobile-first)

**Edição:**

- `frontend/src/app/components/shell/shell.html`
  - `<mat-sidenav>` muda para `mode="over"` em compact; `mode="side"` em medium/expanded
  - `[opened]` é vinculado a signal `sidenavOpen` no TS
  - Adicionar `<app-bottom-tab-bar>` no final do template (visível só em compact via `@if`)
- `frontend/src/app/components/shell/shell.ts`
  - Injetar `BreakpointObserver` (Angular CDK, já disponível)
  - Signal `isCompact = signal(false)` populado pelo observer (`observe([Breakpoints.XSmall, Breakpoints.Small])`)
  - Computed `sidenavMode = computed(() => this.isCompact() ? 'over' : 'side')`
  - Computed `sidenavOpened = computed(() => !this.isCompact())` (no desktop, sempre aberto)
  - Toggle hamburger chama `this.sidenavOpen.set(!this.sidenavOpen())`
- `frontend/src/app/components/shell/shell.scss`
  - Usar `@use` (não `@import`) dos tokens via relative path ou SCSS module
  - Em compact: esconder o `<mat-toolbar>` text label do hamburger, deixar só ícone
  - Bottom tab bar: tokens de `--touch-target-min` em todos os botões, padding bottom respeitando `env(safe-area-inset-bottom)` para iOS notch

**Novo componente:**

- `frontend/src/app/components/shell/bottom-tab-bar/bottom-tab-bar.{ts,html,scss}` (standalone)
  - 4 destinos: Dashboard (`/dashboard`), Transações (`/transactions`), "+" (lançar — abre bottom sheet, **placeholder** nesta fase, ver Fase 5 futura), Planejamento (`/planning`)
  - Cada destino: ícone Material + label curto
  - Marca ativo com base em `routerLinkActive` (Angular Router já dá)
  - Visível só em compact via `display: none` em `> #{breakpoint}`
  - 48dp de altura mínima por botão

**Restrições:**

- Não quebrar navegação desktop — expanded zone deve se comportar igual ao atual
- Não usar libs de icon — Material Icons já no projeto
- Sinalizar no `@if` ou no CSS que é mobile-only (acessibilidade: `aria-hidden` quando fora de compact)
- Não chamar `MatBottomSheet` para o "+" neste slice (é placeholder) — Fase 5 dedicada

### Fase 2 — Dashboard mobile

**Edição:**

- `frontend/src/app/features/dashboard/dashboard.html`
  - Cards de resumo (saldo, receitas, despesas) ficam `grid-template-columns: 1fr` em compact, `repeat(2, 1fr)` em medium, `repeat(4, 1fr)` em expanded
  - "Transações Recentes" lista de cards (não tabela) em compact; mantém comportamento atual em expanded
  - Year/month selector vira chip compact em compact; mantém dropdown em expanded
- `frontend/src/app/features/dashboard/dashboard.scss`
  - Remover `@media max-width:768px` ad-hoc existente (linha 40)
  - Adicionar regras usando os novos mixins (`@include bp.compact { ... }`)
  - Aplicar `min-height: var(--touch-target-min)` em qualquer botão/link clicável
- `frontend/src/app/features/dashboard/dashboard.ts`
  - Injetar `BreakpointObserver` se necessário para alternar comportamento do template
  - Manter todos os signals/computed existentes (não regredir lógica)

**Restrições:**

- **Não esconder** dados importantes (números, gráficos) — só ajustar layout
- "Transações Recentes" continua com `.slice(0,5)` (já está — não muda a lógica de negócio)
- Cards devem usar `--space-md` (16px) padding em compact, `--space-lg` (24px) em expanded
- Sem `<any>` TypeScript
- Componentes já são standalone — manter padrão

---

## Arquivos a criar/alterar

| Arquivo | Tipo | Fase | Mudança |
|---|---|---|---|
| `frontend/src/styles/_tokens.scss` | novo | 0 | CSS vars `--space-*`, `--touch-target-min` |
| `frontend/src/styles/_breakpoints.scss` | novo | 0 | SCSS mixins `@mixin compact/medium/expanded` |
| `frontend/src/styles.scss` | edit | 0 | `@use` dos novos partials no topo |
| `frontend/src/app/components/shell/shell.ts` | edit | 1 | BreakpointObserver + signals `isCompact`, `sidenavOpen` |
| `frontend/src/app/components/shell/shell.html` | edit | 1 | `<mat-sidenav [mode]="sidenavMode()">`, bottom tab bar include |
| `frontend/src/app/components/shell/shell.scss` | edit | 1 | Mixins de breakpoint, tokens spacing/touch |
| `frontend/src/app/components/shell/bottom-tab-bar/bottom-tab-bar.ts` | novo | 1 | Componente standalone, 4 destinos |
| `frontend/src/app/components/shell/bottom-tab-bar/bottom-tab-bar.html` | novo | 1 | Template com 4 botões |
| `frontend/src/app/components/shell/bottom-tab-bar/bottom-tab-bar.scss` | novo | 1 | 48dp altura, safe-area-inset-bottom, hidden em expanded |
| `frontend/src/app/features/dashboard/dashboard.html` | edit | 2 | Grid responsivo nos cards |
| `frontend/src/app/features/dashboard/dashboard.scss` | edit | 2 | Remover media ad-hoc, usar mixins |
| `frontend/src/app/features/dashboard/dashboard.ts` | edit (mínimo) | 2 | Só se necessário para alternar template |

**Total**: 6 novos, 5 edits.

---

## Critérios de aceite

### Funcionais

- [ ] App abre sem erro e sem warnings em **iPhone SE (375×667)**, **iPhone 15 Pro (393×852)**, **Pixel 7 (412×915)** via device mode do Chrome DevTools
- [ ] Bottom tab bar aparece em viewport `<720px` com 4 destinos funcionais (Dashboard, Transações, +, Planejamento) e marca o item ativo
- [ ] Drawer colapsa para `mode="over"` em viewport `<720px`; abre/fecha via hamburger; backdrop fecha o drawer
- [ ] Em `≥1024px`, drawer permanece `mode="side"` aberto e bottom tab bar fica oculta
- [ ] Dashboard: cards full-width em compact, 2 colunas em medium, 4 colunas em expanded
- [ ] Tokens `--space-*` e `--touch-target-min` aplicados em **pelo menos 3 componentes** deste slice (shell, dashboard, bottom-tab-bar)
- [ ] Nenhum elemento clicável com altura efetiva `<48px` em compact zone

### Não-funcionais

- [ ] Suíte `npm test -- --watch=false` (Vitest) verde
- [ ] Build `ng build` sem novos warnings de budget / SCSS deprecated
- [ ] Desktop (≥1024px) mantém aparência e comportamento atuais (zero regressão visual)
- [ ] Nenhum `<any>` TypeScript adicionado (regra do projeto)
- [ ] Nenhum `console.log` esquecido
- [ ] Nenhum `Co-Authored-By` em commits (regra do projeto)
- [ ] Commits PT-BR no imperativo, separados por fase (`feat(frontend): ...`)

### Validação manual

- [ ] Chrome DevTools device mode: iPhone SE, iPhone 15 Pro, Pixel 7 — sem overflow horizontal
- [ ] ADB + Chrome `chrome://inspect` em Android real conectado (com `adb reverse tcp:4200 tcp:4200`) — bottom tab bar tátil, drawer com gesto de swipe-back (se aplicável)
- [ ] Desktop inalterado (comparar screenshots antes/depois no ≥1024px)

---

## Riscos abertos

1. **Drawer hamburger e bottom tab bar duplicam affordances**: usuário pode ficar confuso onde cada um leva. Mitigação: regra clara (tab bar = 4 destinos mobile-first; drawer = tudo que é secundário). Documentado no spec, será revisado na validação visual.
2. **Tokens `--space-*` podem conflitar com valores hardcoded em componentes existentes**: o `@media max-width:768px` ad-hoc no `dashboard.scss:40` usa `16px` literal que pode não bater com `--space-md`. Mitigação: refatorar para o token, validar visualmente.
3. **`@use` (SCSS modules) pode quebrar imports legados (`@import` deprecated)**: a skill do projeto não obriga, mas Material 3 + Angular CLI 18+ prefere `@use`. Decisão: usar `@use` para os novos partials, deixar `@import` existente intocado.
4. **Bottom tab bar "placeholder" do "+"** (não chama bottom sheet neste slice) pode confundir o usuário mobile se ele tocar e nada acontecer. Mitigação: clicar no "+" neste slice **mostra um `MatSnackBar` com "Lançamento rápido virá na próxima fase"** — feedback explícito em vez de broken affordance.

---

## Out of scope (próximas fases — issues dedicadas)

| Fase | Tema | Pré-requisito |
|---|---|---|
| **3** | Lista de transações mobile (`<mat-list>` com swipe actions em compact) | Tokens + Shell |
| **4** | Service Worker (shell + leituras stale-while-revalidate) | Shell |
| **5** | Lançar transação como `MatBottomSheet` (substitui placeholder "+") | Shell + Transactions |
| **6** | Planejamento mobile (`availableToSpend` / `dailyAllowance` cards) | Tokens |
| **7** | Storage + biometria (IndexedDB + Credentials API) | — |

`android/` (Kotlin/Compose legado) **não** está em nenhuma fase. Documentado na seção "Achados" como protótipo fora de escopo.

---

## Roadmap de fases (jornada maior)

Esse design é **slice 1 de 7**. Sequência completa aprovada no grill pré-execução:

```
0. Tokens base              ← ESTE SLICE
1. Shell responsivo          ← ESTE SLICE
2. Dashboard mobile          ← ESTE SLICE
3. Service Worker
5. Bottom sheet de transação
6. Planejamento mobile
7. Storage + biometria
```

Cada fase vira issue dedicada, spec curta quando não-trivial, plano SDD, worktree, merge em develop via PR. Definição da skill `fintech-core-change-control`.