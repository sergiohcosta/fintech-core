# Design: Lançamento rápido em bottom sheet (Fase 5 — mobile-friendly)

**Data:** 2026-10-14
**Escopo:** Frontend only — `TransactionQuickSheetComponent` novo + integrar no `BottomTabBarComponent`
**Fase do roadmap:** Slice 3 de 7 (continua jornada mobile-friendly)

---

## Contexto e motivação

Hoje o "+" da bottom tab bar chama `MatSnackBar` placeholder ("Lançamento rápido virá na próxima fase"). O fluxo mobile real — *ver saldo → tocar + → lançar transação → ver saldo novo* — está quebrado pela metade. O usuário mobile precisa conseguir lançar uma transação em 3 toques no máximo.

A rota `/transa/new` existe e funciona, mas é **rota full-screen** — obriga o usuário a sair do dashboard, perder contexto, e clicar em "Voltar" para ver o saldo atualizado. No mobile isso é killer de UX.

Esse design entrega o **lançamento rápido como bottom sheet modal**, abre do "+" da tab bar, com drag-to-dismiss. Câmera + upload de comprovante ficam para fase dedicada (escopo cortado — ver "Riscos").

---

## Decisões já tomadas (resumo do grill pré-execução)

| # | Decisão | Origem |
|---|---|---|
| Forma | Bottom sheet modal (`MatBottomSheet`), **não** rota full-screen | Q11 |
| Trigger | Botão "+" da bottom tab bar (substitui snackbar placeholder) | Q11 |
| Câmera | **Cortada desta fase** (ver Riscos) | Q10 confirmou, mas escopo MVP sem upload |
| Drag-to-dismiss | Default do Material BottomSheet | Q11 |
| Token | Reusa tokens do slice 1 (`--space-*`, `--touch-target-min`, mixin `bp.compact`) | Q15 |
| Sem libs novas | — | padrão do projeto |

---

## Mudanças concretas

### Novo componente `TransactionQuickSheetComponent`

Standalone em `frontend/src/app/features/transaction/transaction-quick-sheet/`.

**Form (ReactiveForms)** com 5 campos:

| Campo | Tipo | Validação | UI |
|---|---|---|---|
| `amount` | number | obrigatório, > 0 | `<input type="number" inputmode="decimal">` (teclado numérico no mobile) |
| `type` | enum (`INCOME` \| `EXPENSE` \| `TRANSFER`) | obrigatório | `<mat-button-toggle-group>` (segmented control — toque rápido) |
| `accountId` | UUID | obrigatório | `<mat-select>` com contas ativas do tenant |
| `categoryId` | UUID | obrigatório | `<mat-select>` com categorias filtradas pelo tipo |
| `description` | string | opcional, ≤ 200 chars | `<input matInput>` com contador |

**Comportamento**:
- Abre com `MatBottomSheet.open(TransactionQuickSheetComponent, { panelClass: 'quick-entry-sheet' })`
- Drag-down fecha (Material default)
- Submit chama `TransactionService.create(payload)` (verificar nome exato lendo `frontend/src/app/features/transaction/`)
- Sucesso: fecha sheet, `MatSnackBar` "Transação lançada", emite evento `transacaoCriada` num service simples (ex: `TransactionEventsService`) para o dashboard refazer o fetch via signals
- Erro: mantém sheet aberto, snackbar com mensagem, foco volta no campo errado
- Sem upload de comprovante nesta fase (placeholder removido)

### Editar `BottomTabBarComponent`

Substituir `showQuickEntryNotice()` por:

```ts
openQuickEntry(bottomSheet: MatBottomSheet): void {
  bottomSheet.open(TransactionQuickSheetComponent, {
    panelClass: 'quick-entry-sheet',
  });
}
```

Importe `MatBottomSheet` de `@angular/material/bottom-sheet` no botão "+".

### Style da sheet

- `panelClass: 'quick-entry-sheet'` aplica CSS global em `frontend/src/styles.scss` (ou no SCSS do componente)
- Mobile-first: largura 100% em compact, max-width 480px em expanded
- Padding: `var(--space-md)` em compact, `var(--space-lg)` em expanded
- Botão submit: `min-height: var(--touch-target-min)`, full-width em compact
- Input altura: `var(--touch-target-min)` (48dp mínimo)
- Drag handle visual no topo (Material já dá, customizar se quiser)

---

## Arquivos a criar/alterar

| Arquivo | Tipo | Mudança |
|---|---|---|
| `frontend/src/app/features/transaction/transaction-quick-sheet/transaction-quick-sheet.ts` | novo | Standalone component, form, submit logic |
| `frontend/src/app/features/transaction/transaction-quick-sheet/transaction-quick-sheet.html` | novo | Template do form |
| `frontend/src/app/features/transaction/transaction-quick-sheet/transaction-quick-sheet.scss` | novo | Estilos (padding, tokens) |
| `frontend/src/app/components/shell/bottom-tab-bar/bottom-tab-bar.ts` | edit | Trocar `showQuickEntryNotice()` por `openQuickEntry()` |
| (opcional) `frontend/src/app/core/services/transaction-events.ts` | novo | Service simples com signal `transacaoCriada` para dashboard refazer fetch |

Total: 3-4 novos, 1 edit.

---

## Critérios de aceite

### Funcionais

- [ ] Botão "+" do bottom tab abre sheet modal em **<300ms** (perceptualmente instantâneo)
- [ ] Sheet tem 5 campos: amount (number), type (toggle), account (select), category (select), description (text)
- [ ] Submit válido chama API e fecha sheet
- [ ] Submit inválido mantém sheet aberto com mensagens inline (`mat-error`)
- [ ] Sucesso mostra snackbar "Transação lançada"
- [ ] Erro (4xx/5xx) mostra snackbar com mensagem, sheet mantém aberto
- [ ] Drag-down fecha sheet sem submeter
- [ ] Dashboard refaz o fetch automaticamente após sucesso (via service de eventos ou via Router refresh)
- [ ] **Sem regressão** na rota `/transa/new` (continua existindo para casos full-screen)

### Não-funcionais

- [ ] Build verde (`npm run build`)
- [ ] 365+ testes Vitest verde
- [ ] Sem `<any>` TypeScript
- [ ] Sem `console.log`
- [ ] Sem `Co-Authored-By`
- [ ] Tokens `--space-*` e `--touch-target-min` aplicados
- [ ] Mixin `bp.compact` aplicado para layout mobile-first
- [ ] Comentário pedagógico no topo do TS novo

### Validação manual

- [ ] Compact (≤375px): sheet full-width, inputs 48dp, botão submit full-width
- [ ] Expanded (≥1024px): sheet max-width 480px, centralizado, layout confortável
- [ ] Teclado numérico no mobile ao tocar no campo amount
- [ ] Drag-down fecha sem perder dados (UX de "swipe to dismiss" do Material)

---

## Riscos abertos

1. **Sem upload de comprovante** (câmera cortada): usuário mobile não pode anexar foto agora. Mitigação: botão "+" continua fazendo sentido, formulário funciona; comprovante entra em fase dedicada (`transaction-attachment` ou estende Fase 7). Documentado em issue futura.
2. **Categorias filtra por tipo**: precisa o `categoryId` ficar desabilitado/limpo quando `type` muda. UX: implementa via `valueChanges` que limpa categoria ao trocar tipo.
3. **Submit falha de rede** (offline): com Fase 3 (SW), GETs de leitura funcionam offline mas POSTs ainda exigem rede. Mitigação: snackbar "Sem conexão — tente novamente". Fase 7 (fila offline) cobre.
4. **Service de eventos** (signal compartilhado): evita polling. Se o service novo for overkill, alternativa é o dashboard re-fetch em `Router.events` ou `NavigationEnd`. Decisão fica pro designer.
5. **Atualização do dashboard após submit**: se o dashboard usa `toSignal(...summary$)` + `toObservable`, refazer o trigger exige chamar o observable de novo. Mais simples: signal `refreshTrigger = signal(0)` no service, computar `summary$ = toObservable(computed(() => [this.refreshTrigger(), ...]))`. Decisão do designer.

---

## Out of scope (próximas fases)

| Fase | Tema | Relação |
|---|---|---|
| **4** | Lista de transações mobile (`<mat-list>` + swipe actions) | Pré-requisito: Fase 5 dá o caminho de criação |
| **6** | Planejamento mobile (`availableToSpend` cards) | Independente |
| **7** | Storage + biometria + fila offline (c/ retry de POSTs) | Casa com a sheet — submit retry |
| futura | Upload de foto de comprovante (câmera) | Estende esta fase; botão entra no sheet |

---

## Notas de implementação pro @designer

- Caminho da worktree: `/home/sergio/projetos/fintech-core/.worktrees/quick-entry-bottom-sheet`. Use `git -C ...` em todos os comandos git (cwd não persiste).
- Use `git -C ...` em todos os comandos git (cwd não persiste entre chamadas Bash).
- Leia `frontend/src/app/features/transaction/transaction-form/transaction-form.ts` para ver o service exato de criar transação (e tipo do payload). Reuse o que fizer sentido, não duplique.
- O `TransactionService` deve estar em `frontend/src/app/features/transaction/` ou `core/api/` (orval generated). Descubra o nome lendo.
- 2 commits separados: `feat(transaction): cria quick entry bottom sheet` e `feat(shell): integra sheet no botao + da tab bar`. Ou 1 commit só se preferir — fica a critério.
- Mensagens PT-BR imperativas, sem `Co-Authored-By`.