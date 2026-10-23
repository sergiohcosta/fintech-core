# Spec de Design: Usabilidade Mobile — Data na Transação Rápida & Desbloqueio de Scroll na Listagem

**Data:** 19 de Outubro de 2026  
**Status:** Pronto para Implementação  
**Autor:** Antigravity (Arquiteto & Executor)  
**Contexto:** Melhorias críticas de usabilidade mobile (transação rápida com data e rolagem fluida de transações)  

---

## 1. Contexto & Diagnóstico dos Problemas

### 1.1. Ponto 1: Transação Rápida Sem Seletor de Data (`transaction-quick-sheet`)
* **Falha**: Em `frontend/src/app/features/transaction/transaction-quick-sheet/transaction-quick-sheet.ts:176`, o envio de transações e transferências utiliza `const date = formatLocalDate(new Date())` fixo ("hardcoded"). Não há controle `date` no formulário e nenhum campo no template HTML.
* **Impacto**: O usuário não consegue registrar despesas ocorridas ontem, no final de semana ou em qualquer data diferente de "hoje" através do lançamento rápido mobile.
* **Solução**:
  1. Adicionar o controle `date` no `FormGroup` com valor padrão `new Date()` e validador `Validators.required`.
  2. Adicionar métodos auxiliares:
     - `setDateToday()`: define a data como hoje.
     - `setDateYesterday()`: define a data como ontem.
     - `isDateToday()`, `isDateYesterday()`: computed signals para estado visual ativo dos atalhos.
  3. No template:
     - Adicionar atalhos táteis rápidos (chips/botões "Hoje" e "Ontem").
     - Adicionar campo de data com `matDatepicker` integrado para seleção flexível de qualquer data.
  4. No método `submit()`:
     - Usar `formatLocalDate(value.date)` para transferências e transações.

### 1.2. Ponto 2: Rolagem Bloqueada na Lista de Transações (`transaction-list`)
* **Falha**: Em `frontend/src/app/features/transaction/transaction-list/transaction-list.html:138-165`, cada cartão de transação possui a diretiva `@angular/cdk/drag-drop` (`cdkDrag cdkDragLockAxis="x"`), combinada com listeners de evento de ponteiro (`pointerdown`, `pointermove`, `pointerup`, `pointercancel`, `pointerleave`) para emular long-press.
* **Impacto**: 
  1. O CDK Drag intercepta os eventos `touchstart` e `touchmove` do navegador. Mesmo com regras CSS de `touch-action: pan-y !important`, o motor de drag do CDK e os handlers de ponteiro disputam o gesto com a rolagem vertical nativa, tornando a rolagem dura, travada ou completamente inoperante em telas touch e mobile.
  2. O swipe horizontal nos cartões tem pouca precisão e gera risco de disparo acidental.
  3. Cada cartão já possui um botão visível de 3 pontos (`more_vert` / `.tx-menu-trigger`) que abre o menu/Action Sheet com ações completas (Editar, Excluir, etc.).
* **Solução**:
  1. Remover `cdkDrag`, `cdkDragLockAxis`, `[cdkDragFreeDragPosition]`, `(cdkDragEnded)` dos cartões.
  2. Remover `(pointerdown)`, `(pointermove)`, `(pointerup)`, `(pointercancel)`, `(pointerleave)` e a lógica de timer de long-press (`startLongPress`, `moveLongPress`, `endLongPress`, `cancelLongPress`).
  3. Remover os wrappers `.swipe-shell`, `.swipe-actions` e o estado `swipedRow`.
  4. Manter o cartão como um elemento clicável limpo:
     - Clique em grupo parcelado: expande/recolhe as parcelas (`toggleExpand`).
     - Clique em transação comum ou no botão de 3 pontos (`more_vert`): abre o menu de ações contextuais (`openTransactionMenu`).
  5. Remover o CSS de swipe e restaurar `touch-action: auto` para garantir rolagem 100% nativa e fluida com aceleração do navegador.

---

## 2. Plano de Modificações Detalhado

### 2.1. `transaction-quick-sheet`
- `transaction-quick-sheet.ts`:
  - Importar `MatDatepickerModule`, `MatNativeDateModule` e configurar `provideNativeDateAdapter()` nos providers.
  - Adicionar controle `date` no `FormGroup`.
  - Adicionar helpers `setDateToday()`, `setDateYesterday()`, `isToday()`, `isYesterday()`.
  - Atualizar `submit()` para passar `formatLocalDate(value.date)`.
- `transaction-quick-sheet.html`:
  - Incluir bloco de seleção de data com atalhos "Hoje" e "Ontem" e `mat-form-field` com `matDatepicker`.
- `transaction-quick-sheet.scss`:
  - Estilos para `.date-shortcuts` e integração visual harmoniosa com o tema Material 3.
- `transaction-quick-sheet.spec.ts`:
  - Adicionar testes de unidade para inicialização da data, alteração por atalhos e envio no payload.

### 2.2. `transaction-list`
- `transaction-list.html`:
  - Simplificar `#transactionCard`: eliminar `.swipe-shell`, `.swipe-actions`, `cdkDrag` e pointer handlers.
- `transaction-list.ts`:
  - Remover `DragDropModule` dos imports do componente.
  - Remover propriedades e métodos de swipe e longpress (`swipedRow`, `swipePosition`, `onSwipeEnd`, `closeSwipe`, `swipeEdit`, `swipeDelete`, `startLongPress`, `moveLongPress`, `endLongPress`, `cancelLongPress`).
  - Redirecionar ação de exclusão do menu para chamar diretamente `confirmCompactDelete(transaction)`.
  - Simplificar `onCompactCardClick` para expandir grupo de parcelas se for grupo, ou abrir o menu se for item simples.
- `transaction-list.scss`:
  - Remover estilos legados de swipe (`.swipe-shell`, `.swipe-actions`, `.tx-card touch-action`).
- `transaction-list.spec.ts`:
  - Remover testes de `resolveSwipeAction` e garantir que todos os testes da listagem passem.

---

## 3. Verificação & Critérios de Aceite
1. **Transação Rápida**:
   - É possível lançar com data de hoje (padrão) com um único toque.
   - É possível alternar para ontem com um toque no atalho "Ontem".
   - É possível abrir o datepicker e selecionar qualquer data do calendário.
2. **Rolagem da Listagem**:
   - A listagem de transações rola de forma 100% fluida, sem nenhum atraso ou resistência ao deslizar o dedo na vertical.
   - Os 3 pontos (`more_vert`) abrem o menu de ações imediatamente.
3. **Suíte de Testes**:
   - Todos os testes unitários do Vitest executam verde (`npm --prefix frontend test -- --watch=false`).
   - Build de produção (`npm --prefix frontend run build`) bem-sucedido.
