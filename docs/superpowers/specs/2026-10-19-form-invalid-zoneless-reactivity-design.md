# Spec de Design: Reatividade de Validade de Formulários em Zoneless (Issue #149)

**Issue:** #149 — `bug(frontend): form.invalid lido direto no template nao e reativo (zoneless)`  
**Data:** 19 de Outubro de 2026  
**Status:** Pronto para Implementação  
**Autor:** Antigravity (Arquiteto & Executor)  

---

## 1. Contexto & Diagnóstico da Falha

O projeto opera com **Angular 21** e detecção de mudanças puramente **Zoneless** (`provideZonelessChangeDetection()`), sem `zone.js`.

Em Zoneless, o framework agenda ciclos de renderização exclusivamente através de:
1. Notificações de Signals consumidos no template (quando um Signal emite novo valor, sua view correspondente é marcada como dirty).
2. Eventos ouvidos no template (`(click)`, `(input)` etc.).
3. Chamadas explícitas de `ChangeDetectorRef.markForCheck()`.

### Onde ocorre a falha:
Em diversos formulários da aplicação, os botões de ação utilizavam bindings diretos como `[disabled]="form.invalid"`:
- `planning/budget-item-form.html:26,63`
- `planning/recurring-item-form.html:60`
- `team/invite-dialog.html:24`
- `category/category-form.html:69`
- `account/account-form.html:84`
- `auth/login.html:25`
- `auth/register.html:46`
- `auth/forgot-password.html:18`
- `auth/accept-invite.html:51`
- `auth/reset-password.html:17,65`
- `transaction/transaction-quick-sheet.html:105`
- `planning/planning-settings.ts:35`

### O Problema:
`form.invalid` (e `form.valid`) são propriedades síncronas / getters simples de instâncias de `AbstractControl` no `ReactiveFormsModule`. Eles **não são Signals**.

Quando ocorrem alterações programáticas ou assíncronas no formulário — tais como:
- `form.setValue()` ou `form.patchValue()`;
- Autofill do navegador;
- Validação assíncrona ou cross-field que atualiza a árvore de controles fora de um listener direto de template;
- Desativação/habilitação de campos dinâmicos (`control.enable()`, `control.disable()`);

O Angular Zoneless **não é notificado** da mudança de validade. O botão de submit/ação permanece com estado obsoleto (*stale* — travado ou liberado indevidamente) até que ocorra algum outro evento arbitrário no DOM.

---

## 2. Solução Técnica

### 2.1. Criação do Utilitário Central `form.utils.ts`
Criar `frontend/src/app/core/form.utils.ts` expondo helpers reativos que convertem `statusChanges` em Signals do Angular:

```typescript
import { computed, Injector, Signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl } from '@angular/forms';

export interface FormSignalOptions {
  injector?: Injector;
}

/**
 * Converte a invalidade de um AbstractControl (FormGroup, FormControl, FormArray)
 * em um Signal booleano reativo.
 *
 * Em Zoneless change detection, control.invalid é uma propriedade comum que não notifica
 * o framework sobre atualizações. Esta função conecta control.statusChanges a um Signal
 * com initialValue baseado no status síncrono atual.
 */
export function formInvalidSignal(
  control: AbstractControl,
  options?: FormSignalOptions,
): Signal<boolean> {
  const status = toSignal(control.statusChanges, {
    initialValue: control.status,
    injector: options?.injector,
  });
  return computed(() => status() === 'INVALID');
}

/**
 * Converte a validade de um AbstractControl em um Signal booleano reativo.
 */
export function formValidSignal(
  control: AbstractControl,
  options?: FormSignalOptions,
): Signal<boolean> {
  const status = toSignal(control.statusChanges, {
    initialValue: control.status,
    injector: options?.injector,
  });
  return computed(() => status() === 'VALID');
}
```

### 2.2. Migração dos Componentes e Templates

Para cada componente afetado:
1. Declarar o sinal de invalidade com `formInvalidSignal(control)` na inicialização da classe.
2. Substituir `form.invalid` por `isInvalid()` (ou nome descritivo correspondente) no template HTML.

| Componente | Sinal Exposto | Template Binding |
|---|---|---|
| `BudgetItemFormComponent` | `isCycleFormInvalid`, `isItemFormInvalid` | `[disabled]="isCycleFormInvalid()"`, `[disabled]="isItemFormInvalid()"` |
| `RecurringItemFormComponent` | `isInvalid` | `[disabled]="isInvalid()"` |
| `InviteDialogComponent` | `isInvalid` | `[disabled]="isInvalid() || state() === 'submitting'"` |
| `CategoryForm` | `isInvalid` | `[disabled]="isInvalid()"` |
| `AccountForm` | `isInvalid` | `[disabled]="isInvalid() || saving()"` |
| `LoginComponent` | `isInvalid` | `[disabled]="isInvalid() || isLoading()"` |
| `RegisterComponent` | `isInvalid` | `[disabled]="isInvalid() || isLoading()"` |
| `ForgotPasswordComponent` | `isInvalid` | `[disabled]="isInvalid() || isLoading()"` |
| `AcceptInviteComponent` | `isInvalid` | `[disabled]="isInvalid() || state() === 'submitting'"` |
| `ResetPasswordComponent` | `isTokenFormInvalid`, `isFormInvalid` | `[disabled]="isTokenFormInvalid()"`, `[disabled]="isFormInvalid() || state() === 'submitting'"` |
| `TransactionQuickSheetComponent` | `isInvalid` | `[disabled]="isInvalid() || saving()"` |
| `PlanningSettingsComponent` | `isStartDayInvalid` | `[disabled]="isStartDayInvalid() || saving()"` |

### 2.3. Ajuste Específico no `CategoryForm` e `TransactionQuickSheet`
- No `CategoryForm`: inicializar `form` na declaração do campo (em vez de `ngOnInit`) para permitir que `isInvalid` seja instanciado com o contexto de injeção correto. Ao carregar dados no modo edição, chamar `this.form.updateValueAndValidity()` para emitir o status atualizado.
- No `TransactionQuickSheetComponent`: em `updateTypeFields`, garantir que `this.form.updateValueAndValidity()` seja executado para atualizar a árvore de status ao alternar entre TRANSFER, EXPENSE e INCOME.

---

## 3. Plano de Testes & Verificação

1. **Testes Unitários do Utilitário (`form.utils.spec.ts`)**:
   - `formInvalidSignal` reflete o status inicial (`true` quando inválido, `false` quando válido).
   - `formInvalidSignal` atualiza reativamente quando o valor do controle é alterado (`setValue`/`patchValue`).
   - `formValidSignal` reflete adequadamente a validade positiva.
2. **Testes de Regressão de Componentes**:
   - Executar suíte completa do Vitest (`npm --prefix frontend test -- --watch=false`).
   - Garantir que todos os 397+ testes continuam passando verde (zero regressões).
