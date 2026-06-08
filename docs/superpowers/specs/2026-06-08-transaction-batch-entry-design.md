# Spec: Cadastro em Sequência de Transações

**Data:** 2026-06-08
**Issue:** #50
**Escopo:** Frontend only — zero alterações de backend

---

## Contexto

O formulário de transação (`/transactions/new`) hoje sempre navega para `/transactions` após salvar. Quando o usuário precisa lançar várias transações seguidas (ex: compras do dia, despesas de uma viagem), precisa voltar ao formulário repetidamente e repreencher contexto que não muda entre os lançamentos.

---

## Objetivo

Adicionar um botão secundário **"Salvar e lançar outra"** que salva a transação atual, permanece no formulário e preserva o contexto da sessão de lançamento — reduzindo atrito para entradas em sequência.

---

## Comportamento

### Onde o botão aparece

Apenas quando:
- `!isEditMode()` — não faz sentido em edição
- `mode() === 'TRANSACTION'` — não faz sentido em transferências (contexto muda radicalmente a cada transferência)

### Fluxo ao clicar

1. Valida o formulário (mesma lógica do botão "Salvar")
2. Exibe spinner, desabilita ambos os botões (`saving = true`)
3. Chama a API via `doSave()`
4. **Sucesso:** exibe snackbar "Transação criada com sucesso!", chama `partialReset()`
5. **Erro:** exibe snackbar de erro, `saving.set(false)` — mesmo comportamento atual

### Reset parcial após sucesso

| Campo | Ação | Motivo |
|---|---|---|
| `accountId` | mantém | mesma conta na sessão de lançamentos |
| `date` | mantém | mesmo dia |
| `type` | mantém | mesma natureza (ex: sessão de despesas) |
| `status` | mantém | ex: todos pendentes |
| `categoryId` | mantém | frequente lançar itens da mesma categoria em sequência |
| `description` | zera + reset | único por transação |
| `amount` | zera + reset | único por transação |
| `amountDisplay` | `''` | signal auxiliar da máscara de moeda |
| `isInstallment` | `false` | cada transação começa sem parcelamento |
| `totalInstallments` | `1` | valor default |
| `propagateFields` | `Set` vazio | sem sentido em criação |

`reset()` nos controles de `description` e `amount` (além de `patchValue`) é necessário para limpar o estado `touched`/`dirty` — sem isso os erros de validação aparecem imediatamente no campo vazio após o reset.

Após o reset, o foco vai para o campo `description` para que o usuário possa começar a digitar sem usar o mouse.

---

## Arquitetura

### Arquivos alterados

- `frontend/src/app/features/transaction/transaction-form/transaction-form.ts`
- `frontend/src/app/features/transaction/transaction-form/transaction-form.html`

Nenhum arquivo novo. Nenhuma alteração de backend.

### Refatoração no `.ts`

O `onSubmit()` atual acumula três responsabilidades: montar payload, chamar API e decidir o que fazer após. Extrai-se a construção de payload + chamada HTTP para `doSave()`:

```
doSave(): Observable<any>
  Monta o payload (incluindo cálculo de valor total para parcelas),
  chama createTransaction / createTransfer / updateTransaction.
  Não toca em saving, snackbar ou navegação — responsabilidade exclusiva
  de construir e disparar a requisição.

onSubmit()
  Valida form, saving.set(true), chama doSave().
  No sucesso: navega para /transactions.
  No erro: saving.set(false), exibe snackbar.

onSaveAndAddMore()
  Valida form, saving.set(true), chama doSave().
  No sucesso: snackbar, chama partialReset().
  No erro: saving.set(false), exibe snackbar.

partialReset() [privado]
  form.patchValue({ description: null, amount: null, totalInstallments: 1 })
  form.controls.description.reset()
  form.controls.amount.reset()
  amountDisplay.set('')
  isInstallment.set(false)
  propagateFields.set(new Set())
  Foca o campo description via ViewChild<ElementRef>
```

### Alteração no `.html`

Na `div.form-actions`, adicionar o botão entre "Cancelar" e "Salvar":

```html
@if (!isEditMode() && mode() === 'TRANSACTION') {
  <button mat-stroked-button type="button"
          [disabled]="form.invalid || saving()"
          (click)="onSaveAndAddMore()">
    <mat-icon>add</mat-icon>
    Salvar e lançar outra
  </button>
}
```

`type="button"` é obrigatório para evitar que o clique acione o `(ngSubmit)` do form — `onSaveAndAddMore()` gerencia validação e submit diretamente.

---

## ViewChild para foco

O campo `description` precisa de um `@ViewChild` no template e no componente:

```html
<input #descriptionInput matInput formControlName="description" ... >
```

```ts
@ViewChild('descriptionInput') private descriptionInput?: ElementRef<HTMLInputElement>;

// em partialReset():
this.descriptionInput?.nativeElement.focus();
```

---

## Testes

A lógica de `partialReset()` é testável via `spec.ts` existente:
- Após chamar `partialReset()`, `description` e `amount` devem estar vazios e com `pristine = true`
- `accountId`, `date`, `type`, `status`, `categoryId` devem permanecer com os valores anteriores
- `isInstallment()` deve ser `false`
- `amountDisplay()` deve ser `''`

Não há lógica nova no backend para testar.

---

## O que esta spec não cobre

- Modo TRANSFER: sem botão "Salvar e lançar outra" — cada transferência tem origem/destino específico
- Modo edição: sem botão — não faz sentido "adicionar mais" ao editar
- Parcelamento: o botão funciona normalmente; o estado de parcelamento é zerado no reset como qualquer novo lançamento
