# Transaction Batch Entry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adicionar botão "Salvar e lançar outra" no formulário de transação que salva e reseta parcialmente o form sem navegar, permitindo lançamentos em sequência.

**Architecture:** Refatoração cirúrgica no `TransactionForm` — extrair `doSave()` com o payload + chamada HTTP, adicionar `onSaveAndAddMore()` que chama `doSave()` e depois `partialReset()`, e inserir o botão no template. Nenhuma mudança de backend.

**Tech Stack:** Angular 21 Zoneless, Signals, Reactive Forms, Angular Material 3

---

## Arquivos modificados

- `frontend/src/app/features/transaction/transaction-form/transaction-form.ts` — extrair `doSave()`, adicionar `onSaveAndAddMore()`, `partialReset()`, `ViewChild` para foco
- `frontend/src/app/features/transaction/transaction-form/transaction-form.html` — adicionar `#descriptionInput` no input e botão "Salvar e lançar outra"

---

## Task 1: Extrair `doSave()` do `onSubmit()`

Refactoring puro — nenhuma funcionalidade nova. O objetivo é separar "o que salvar" de "o que fazer depois de salvar".

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-form/transaction-form.ts`

- [ ] **Step 1: Confirmar baseline de testes**

```bash
cd frontend && npx vitest run
```

Resultado esperado: 46 passing, 56 failing (falhas pré-existentes — não relacionadas a este arquivo).

- [ ] **Step 2: Adicionar import de `Observable`**

Em `transaction-form.ts`, adicionar na lista de imports do RxJS (após linha 2):

```ts
import { Observable } from 'rxjs';
```

- [ ] **Step 3: Adicionar método privado `doSave()`**

Inserir antes do método `onSubmit()` (antes da linha `onSubmit(): void`):

```ts
private doSave(): Observable<any> {
  const raw = this.form.getRawValue();

  if (this.isEditMode()) {
    return this.transactionService.updateTransaction(this.transactionId()!, {
      description: raw.description!,
      amount: raw.amount!,
      date: this.toDateString(raw.date as Date),
      type: raw.type as 'INCOME' | 'EXPENSE',
      status: raw.status as 'PENDING' | 'PAID' | 'CANCELLED' ?? undefined,
      categoryId: raw.categoryId ?? undefined,
      accountId: raw.accountId!,
      propagate: this.propagateFields().size > 0 ? Array.from(this.propagateFields()) : undefined
    });
  }

  if (this.mode() === 'TRANSFER') {
    return this.transferService.createTransfer({
      fromAccountId: raw.fromAccountId!,
      toAccountId: raw.toAccountId!,
      amount: raw.amount!,
      date: this.toDateString(raw.date as Date),
      description: raw.description || undefined
    });
  }

  const rawAmount = raw.amount!;
  const totalAmount = this.isInstallment() && this.valueMode() === 'per-installment'
    ? rawAmount * (raw.totalInstallments ?? 1)
    : rawAmount;

  return this.transactionService.createTransaction({
    description: raw.description!,
    amount: totalAmount,
    date: this.toDateString(raw.date as Date),
    type: raw.type as 'INCOME' | 'EXPENSE',
    status: raw.status as 'PENDING' | 'PAID' | 'CANCELLED' ?? undefined,
    categoryId: raw.categoryId ?? undefined,
    accountId: raw.accountId!,
    totalInstallments: this.isInstallment() ? (raw.totalInstallments ?? 1) : 1
  });
}
```

- [ ] **Step 4: Substituir o corpo do `onSubmit()` para usar `doSave()`**

Substituir o método `onSubmit()` inteiro pelo seguinte:

```ts
onSubmit(): void {
  if (this.form.invalid) return;
  this.saving.set(true);

  this.doSave().subscribe({
    next: (result) => {
      let msg: string;
      if (this.isEditMode()) {
        msg = 'Transação atualizada com sucesso!';
      } else if (this.mode() === 'TRANSFER') {
        msg = 'Transferência registrada com sucesso!';
      } else {
        msg = Array.isArray(result) && result.length > 1
          ? `${result.length} parcelas criadas com sucesso!`
          : 'Transação criada com sucesso!';
      }
      this.snackBar.open(msg, 'OK', { duration: 3000 });
      this.router.navigate(['/transactions']);
    },
    error: () => {
      this.saving.set(false);
      this.snackBar.open('Erro ao salvar transação.', 'Fechar', { duration: 5000 });
    }
  });
}
```

- [ ] **Step 5: Confirmar que os testes continuam iguais**

```bash
npx vitest run
```

Resultado esperado: mesmos 46 passing, 56 failing. Nenhuma nova falha.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-form/transaction-form.ts
git commit -m "refactor(transaction-form): extrai doSave() do onSubmit para separar payload de ação pós-save"
```

---

## Task 2: Adicionar `ViewChild` para foco no campo description

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-form/transaction-form.ts`
- Modify: `frontend/src/app/features/transaction/transaction-form/transaction-form.html`

- [ ] **Step 1: Adicionar `ElementRef` ao import do `@angular/core`**

Em `transaction-form.ts`, linha 1, o import atual é:

```ts
import { Component, inject, OnInit, signal, computed, effect, ViewChild } from '@angular/core';
```

Alterar para:

```ts
import { Component, ElementRef, inject, OnInit, signal, computed, effect, ViewChild } from '@angular/core';
```

- [ ] **Step 2: Adicionar campo `descriptionInput` na classe**

Logo após os dois `@ViewChild` existentes (linhas 74–75), adicionar:

```ts
@ViewChild('descriptionInput') private descriptionInput?: ElementRef<HTMLInputElement>;
```

- [ ] **Step 3: Adicionar template reference `#descriptionInput` no input**

Em `transaction-form.html`, linha 109, o input atual é:

```html
<input matInput formControlName="description" placeholder="Ex: Supermercado, Salário..." />
```

Alterar para:

```html
<input #descriptionInput matInput formControlName="description" placeholder="Ex: Supermercado, Salário..." />
```

**Nota:** o `#descriptionInput` em linha 109 se refere ao input da seção TRANSACTION. O input de description da seção TRANSFER (linha 218) não recebe a ref — o botão "Salvar e lançar outra" só existe no modo TRANSACTION.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-form/transaction-form.ts \
        frontend/src/app/features/transaction/transaction-form/transaction-form.html
git commit -m "feat(transaction-form): adiciona ViewChild para foco no campo description"
```

---

## Task 3: Implementar `partialReset()` e `onSaveAndAddMore()`

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-form/transaction-form.ts`

- [ ] **Step 1: Adicionar método privado `partialReset()`**

Inserir após o método `onSaveAndAddMore()` que será criado no próximo step (ou antes dele — ordem não importa desde que ambos fiquem agrupados com os handlers de submit). Inserir logo após `onSubmit()`:

```ts
private partialReset(): void {
  this.form.patchValue({ description: null, amount: null, totalInstallments: 1 });
  this.form.controls.description.reset();
  this.form.controls.amount.reset();
  this.amountDisplay.set('');
  this.isInstallment.set(false);
  this.propagateFields.set(new Set());
  this.saving.set(false);
  // setTimeout garante que o foco ocorre após o Angular processar o reset do form
  setTimeout(() => this.descriptionInput?.nativeElement.focus());
}
```

**Por que `reset()` além de `patchValue`?**
`patchValue` atualiza o valor mas mantém o estado `touched`/`dirty`. Sem `reset()`, os erros de validação apareceriam imediatamente nos campos recém-zerados. `reset()` limpa o estado de validação, devolvendo o campo ao estado pristine.

- [ ] **Step 2: Adicionar método público `onSaveAndAddMore()`**

Inserir entre `onSubmit()` e `partialReset()`:

```ts
onSaveAndAddMore(): void {
  if (this.form.invalid) return;
  this.saving.set(true);

  this.doSave().subscribe({
    next: (result) => {
      const msg = Array.isArray(result) && result.length > 1
        ? `${result.length} parcelas criadas com sucesso!`
        : 'Transação criada com sucesso!';
      this.snackBar.open(msg, 'OK', { duration: 3000 });
      this.partialReset();
    },
    error: () => {
      this.saving.set(false);
      this.snackBar.open('Erro ao salvar transação.', 'Fechar', { duration: 5000 });
    }
  });
}
```

- [ ] **Step 3: Confirmar que os testes continuam iguais**

```bash
npx vitest run
```

Resultado esperado: mesmos 46 passing, 56 failing.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-form/transaction-form.ts
git commit -m "feat(transaction-form): implementa onSaveAndAddMore e partialReset para lançamentos em sequência"
```

---

## Task 4: Adicionar botão no template e verificar comportamento

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-form/transaction-form.html`

- [ ] **Step 1: Adicionar o botão na `div.form-actions`**

A `div.form-actions` atual (linhas 298–308) é:

```html
<div class="form-actions">
  <button mat-stroked-button type="button" routerLink="/transactions">Cancelar</button>
  <button mat-flat-button color="primary" type="submit" [disabled]="form.invalid || saving()">
    @if (!saving()) {
      <mat-icon>save</mat-icon>
    } @else {
      <mat-progress-spinner diameter="18" mode="indeterminate" class="btn-spinner" />
    }
    {{ saving() ? 'Salvando...' : 'Salvar' }}
  </button>
</div>
```

Substituir por:

```html
<div class="form-actions">
  <button mat-stroked-button type="button" routerLink="/transactions">Cancelar</button>

  @if (!isEditMode() && mode() === 'TRANSACTION') {
    <button mat-stroked-button type="button" color="primary"
            [disabled]="form.invalid || saving()"
            (click)="onSaveAndAddMore()">
      <mat-icon>add</mat-icon>
      Salvar e lançar outra
    </button>
  }

  <button mat-flat-button color="primary" type="submit" [disabled]="form.invalid || saving()">
    @if (!saving()) {
      <mat-icon>save</mat-icon>
    } @else {
      <mat-progress-spinner diameter="18" mode="indeterminate" class="btn-spinner" />
    }
    {{ saving() ? 'Salvando...' : 'Salvar' }}
  </button>
</div>
```

**Por que `type="button"`?** Sem esse atributo, um `<button>` dentro de um `<form>` tem `type="submit"` por padrão, o que acionaria o `(ngSubmit)` do form em vez do `(click)`. Com `type="button"`, o clique vai exclusivamente para `onSaveAndAddMore()`.

- [ ] **Step 2: Confirmar que os testes continuam iguais**

```bash
npx vitest run
```

Resultado esperado: mesmos 46 passing, 56 failing.

- [ ] **Step 3: Teste manual — fluxo principal**

Verificar com o backend rodando (`http://localhost:8080/actuator/health` deve retornar UP):

1. Navegar para `http://localhost:4200/transactions/new`
2. Verificar que o botão **"Salvar e lançar outra"** aparece entre "Cancelar" e "Salvar"
3. Preencher: Conta = qualquer conta, Tipo = Despesa, Status = Pendente, Categoria = qualquer, Descrição = "Teste 1", Valor = 100
4. Clicar **"Salvar e lançar outra"**
5. Verificar:
   - Snackbar "Transação criada com sucesso!" aparece
   - Permanece na mesma página (`/transactions/new`)
   - `description` está vazio, `amount` está vazio
   - `accountId`, `date`, `type`, `status`, `categoryId` mantêm os valores anteriores
   - Foco está no campo description
6. Preencher Descrição = "Teste 2", Valor = 50 e clicar **"Salvar"** (botão normal)
7. Verificar que navega para `/transactions` e ambas as transações aparecem na lista

- [ ] **Step 4: Teste manual — modo edição (botão não deve aparecer)**

1. Clicar em editar qualquer transação existente
2. Verificar que apenas "Cancelar" e "Salvar" aparecem — **sem** "Salvar e lançar outra"

- [ ] **Step 5: Teste manual — modo transferência (botão não deve aparecer)**

1. Em `/transactions/new`, selecionar aba "Transferência"
2. Verificar que apenas "Cancelar" e "Salvar" aparecem — **sem** "Salvar e lançar outra"

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-form/transaction-form.html
git commit -m "feat(transaction-form): adiciona botão 'Salvar e lançar outra' para cadastro em sequência"
```
