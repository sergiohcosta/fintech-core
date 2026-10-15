# Design: Lista de transações mobile (Fase 4 — mobile-friendly)

**Data:** 2026-10-14
**Escopo:** Frontend only — refatorar `transaction-list` para cards full-width + swipe actions em compact; desktop inalterado
**Fase do roadmap:** Slice 4 de 7

---

## Contexto e motivação

`transaction-list` é a tela mais usada do app mobile (per grill Q2: "conferir transações recentes" é a 2ª tarefa mais comum no smartphone). Hoje ela é uma `<mat-table>` com 10+ colunas e sort — funciona bem no desktop mas no smartphone:

- 10 colunas em 375px = fonte ilegível, scroll horizontal, elementos <32dp para toque
- Sem swipe, sem long press menu, sem `mat-list`/`mat-card` card-based layout
- Filtros são diálogos modais (top-center desktop pattern) que tampam a tela no mobile
- Selection em massa exige checkboxes que disputam espaço com texto

Esse design troca `<mat-table>` por uma **lista de cards full-width com gestos de swipe** para excluir/editar, mantém o desktop inalterado, e adapta os filtros para um drawer inferior (`MatBottomSheet`) no mobile. Sem mexer em backend, sem mexer em DTOs de model.

---

## Decisões já tomadas (resumo do grill pré-execução)

| # | Decisão | Origem |
|---|---|---|
| Lista compact | Cards full-width em `<mat-list>` / `<mat-card>`, **não** tabela | Q6 |
| Gestos | Swipe actions via Angular CDK Drag (já no projeto) | Q6 |
| Substitutos do hover | `@media (hover: hover)` divisor; long press para menu | Q7 |
| Tokens | Reusa `--space-*`, `--touch-target-min`, mixin `bp.compact` | Q15 |
| Sem libs novas | — | padrão |

---

## Mudanças concretas

### 1. Estrutura dual no `transaction-list.html`

Usar breakpoint dinâmico no TS (`BreakpointObserver` injeta signal `isCompact`) pra alternar **dois templates**:

```html
@if (isCompact()) {
  <mat-list class="tx-cards">
    @for (t of transactions(); track t.id) {
      <mat-list-item class="tx-card" ...> ... </mat-list-item>
    }
  </mat-list>
} @else {
  <table mat-table class="tx-table">
    <!-- código atual intocado -->
  </table>
}
```

A lógica de negócio, sort, paginação server-side e signals ficam **iguais — mesmo**.

### 2. Cards em compact — campos visíveis

| Campo | Mostrado em compact | Como |
|---|---|---|
| Descrição | ✓ | Nome, truncate se > 30ch |
| Valor | ✓ | Destaque à direita, cor por tipo (income/expense/transfer) |
| Data | ✓ | `dd/MM` (mais compacto que `dd/MM/yyyy`) |
| Categoria | ✓ | `media-chip` à direita (mesmo padrão do dashboard) |
| Conta | ✓ | Pequena, abaixo da descrição (se diferente da conta origem de TRANSFER) |
| Conta destino (transfer) | ✓ | "→ Conta destino" |
| Parcela atual | ✓ | `2/12` se aplicável |
| Sort header | ✗ (esconder) | Ordenação via menu contextual (3-dots) |
| Selection checkbox | ✗ (esconder) | Selection em massa só desktop |
| Ações inline (editar/excluir) | ✗ (esconder botão) | Swipe actions substituem |
| Detalhe expansível | ✓ | Tap no card → expande (mesmo padrão do desktop) |

### 3. Swipe actions via CDK Drag

Cada card tem CDK Drag horizontal (motion esquerda = excluir, direita = editar). Threshold: 96px (mesmo padrão do drag-to-dismiss da Fase 5).

```html
<mat-list-item
  cdkDrag
  (cdkDragEnded)="onSwipeEnd($event, t)"
  class="tx-card"
  [class.swiped-left]="t.swipedLeft"
  [class.swiped-right]="t.swipedRight"
>
  ...
</mat-list-item>
```

Ações reveladas no swipe:
- **Esquerda (reveal à direita)**: botão "Excluir" vermelho
- **Direita (reveal à esquerda)**: botão "Editar" azul

Confirmação de exclusão: `MatBottomSheet` de confirmação (mesmo padrão do `installment-confirm-dialog`).

### 4. Filtros — adaptar para compact

Os filtros atuais (`transaction-filters/`) são diálogo modal. Em compact:

- Esconder botão flutuante de filtro no compact
- Adicionar botão "Estatizar" (icon `filter_alt`) na bottom tab bar ou como floating action button no header
- Tap → `MatBottomSheet.open(TransactionFiltersComponent)` (componente atual adaptado)
- Em desktop, manter diálogo modal

### 5. Long press menu

Long press no card (≥500ms) abre `MatMenu` com: Editar, Excluir, Duplicar. Funciona em ambas as zonas (touch + desktop).

---

## Arquivos a criar/alterar

| Arquivo | Tipo | Mudança |
|---|---|---|
| `frontend/src/app/features/transaction/transaction-list/transaction-list.ts` | edit | Injetar `BreakpointObserver`, signal `isCompact`, branches `@if` no template |
| `frontend/src/app/features/transaction/transaction-list/transaction-list.html` | edit | Dois blocos `@if (isCompact())` vs `@else` |
| `frontend/src/app/features/transaction/transaction-list/transaction-list.scss` | edit | Estilos dos cards (compact) + tabela (preservar) |
| (opcional) `frontend/src/app/features/transaction/transaction-filters/transaction-filters.ts` | edit | Adaptar para abrir como `MatBottomSheet` em compact |

Total: 3 edits principais. Sem novos componentes.

---

## Critérios de aceite

### Funcionais

- [ ] Em compact (<720px): lista renderiza como `<mat-list>` de cards full-width
- [ ] Em expanded (≥1024px): `<mat-table>` aparece idêntica à versão atual (sem regressão)
- [ ] Swipe esquerda ≥96px revela botão "Excluir" e toca a confirmação
- [ ] Swipe direita ≥96px revela botão "Editar" e abre o form
- [ ] Tap no card expande detalhe (mesmo comportamento do expand atual)
- [ ] Long press ≥500ms abre menu com Editar/Excluir/Duplicar
- [ ] Filtros abrem como bottom sheet em compact; como modal em expanded
- [ ] Sort funciona em ambas as zonas (menu contextual em compact, header em expanded)
- [ ] Selection em massa só aparece em expanded

### Não-funcionais

- [ ] Build verde (`npm run build`)
- [ ] 371+ testes Vitest verde
- [ ] Sem `<any>` TypeScript
- [ ] Sem `console.log`
- [ ] Sem `Co-Authored-By`
- [ ] Tokens `--space-*` e `--touch-target-min` aplicados
- [ ] Mixin `bp.compact` aplicado
- [ ] Desktop **zero regressão** (comparar antes/depois mentalmente — 1 parágrafo te diz se quebrou)
- [ ] Comentário pedagógico no topo dos TS novos/editados explicando "por que" das decisões

### Validação manual

- [ ] Compact (375px): cards full-width, swipe funciona, long press é menu, filtros em bottom sheet
- [ ] Expanded (≥1024px): tabela 10+ colunas idêntica ao atual, sort por coluna, filtros em modal
- [ ] iOS Safari: long press não dispara menu de contexto nativo do browser (preventDefault em `contextmenu`)

---

## Riscos abertos

1. **Perda de densidade em compact**: usuário power-user que vê muitas transações por tela no desktop vai ver menos em compact. Mitigação: scroll vertical é natural, swipe é rápido; aceitável.
2. **Swipe vs scroll horizontal**: o CDK Drag horizontal pode conflitar com scroll vertical em listas longas. Mitigação: lock axis `cdkDragLockAxis="x"` (Material CDK default) — drag só horizontal, scroll vertical livre.
3. **CDK Drag + mat-list-item**: podem ter z-index/transform conflitos. Mitigação: testar em device real antes de promover pra prod.
4. **Detalhe expansível em card**: tap aciona swipe? Pode conflitar. Decisão: tap expande (com `cdkDragStartDelay` se preciso); long press abre menu.
5. **Sort em compact**: UX de sort sem header de coluna. Mitigação: menu contextual 3-dots com "Ordenar por: data | valor | descrição".
6. **Selection em massa escondida**: usuário mobile perde esse atalho. Aceitável — seleção em massa é padrão desktop.

---

## Out of scope (próximas fases)

| Fase | Tema | Relação |
|---|---|---|
| **6** | Planejamento mobile (`availableToSpend` cards) | Independente |
| **7** | Storage + biometria + fila offline | Independente |
| futura | Edição inline de transação (long press → form em sheet) | Sobre esta Fase 4 |

---

## Notas de implementação pro @designer

- Caminho da worktree: `/home/sergio/projetos/fintech-core/.worktrees/transactions-mobile-list`
- Use `git -C ...` em todos os comandos git (cwd não persiste entre chamadas Bash)
- O `transaction-list.html` tem ~370 linhas hoje (segundo `@exp-1`) — preserve o bloco `@else` (desktop) intacto byte a byte
- O `transaction-list.ts` provavelmente já tem signals; injete `BreakpointObserver` e adicione `isCompact = signal(false)` sem quebrar a lógica existente
- 2 commits separados: `feat(transaction-list): adiciona variante mobile com cards full-width e swipe` + `feat(transaction-filters): adapta abertura para bottom sheet em compact`
- Mensagens PT-BR imperativas, sem `Co-Authored-By`
- Build verde (`npm run build`) + 371+ testes Vitest verde (`npm test -- --watch=false`, NUNCA `npx vitest` direto)