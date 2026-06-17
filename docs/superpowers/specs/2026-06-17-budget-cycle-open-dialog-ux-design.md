# Design: Melhoria do Dialog "Abrir Novo Ciclo de Planejamento"

**Data:** 2026-06-17  
**Escopo:** Frontend — `budget-cycle-open-dialog` (Angular 21 Zoneless)  
**Status:** Aprovado

## Problema

O dialog atual de abertura de ciclo é funcional mas crua:
- O campo de saldo de abertura não explica o que é nem de onde vem o valor sugerido
- Os itens recorrentes e parcelas não mostram datas — o usuário não sabe quando cada item cai no ciclo
- Transações já lançadas no período não são visíveis (aparecem como não planejadas após abrir, sem aviso prévio)
- Não há saldo projetado — o usuário confirma sem saber o resultado esperado do ciclo
- O cabeçalho do período tem peso visual insuficiente

## Solução: Enriquecer o dialog existente

Mantém o padrão `MatDialog` e a estrutura de dados atual. Nenhuma mudança de rota, stepper ou backend necessária — exceto uma segunda chamada à API de transações.

---

## Seção 1 — Cabeçalho do Período

**Atual:** `period-row` com ícone `date_range` + texto plano (ex: "01/06/2026 — 30/06/2026").

**Proposto:** bloco tipográfico em duas linhas:
- Linha 1: nome do mês + ano em `font-size: 1.25rem`, `font-weight: 600` (ex: "Junho 2026")
- Linha 2: range completo em texto secundário `on-surface-variant` (ex: "01/06/2026 – 30/06/2026")

Sem card, sem borda — só hierarquia tipográfica.

---

## Seção 2 — Saldo de Abertura

**Atual:** `mat-form-field` com hint mostrando o valor sugerido em BRL.

**Proposto:**
- Label: `Saldo de abertura (R$)` com ícone `info_outline` ao lado (mat-icon inline, cursor pointer)
- `matTooltip` no ícone: *"O saldo de abertura representa o dinheiro disponível no início do ciclo. O valor sugerido é a soma das suas contas marcadas como 'conta no saldo líquido' com transações pagas."*
- `mat-hint`: *"Sugerido: R$ X,XX — baseado nas suas contas líquidas"* (substitui o hint atual que só mostrava o valor)
- O campo continua editável e obrigatório

**Reatividade:** o valor digitado no campo alimenta o cálculo do saldo projetado (Seção 5) via `toSignal(openingBalance.valueChanges)` + `computed()`.

---

## Seção 3 — Itens do Preview (Recorrentes e Parcelas)

**Atual:** cada item mostra apenas descrição e valor. A data existe na resposta da API mas não é renderizada.

**Proposto:** adicionar data em cada linha, antes do valor:
- Itens recorrentes: exibir `expectedDate` formatado como `dd/MM`
- Parcelas do cartão: exibir `dueDate` formatado como `dd/MM`
- Layout da linha: `[descrição flex-1] [data muted] [valor colorido]`

Sem mudança de estrutura de dados — ambas as datas já estão nos DTOs `RecurringItemPreview` e `InstallmentItemPreview`.

---

## Seção 4 — Transações Já Lançadas no Período

**Motivação:** ao abrir o ciclo, transações já registradas no período tornam-se automaticamente "despesas não planejadas". O usuário precisa saber que elas existem antes de confirmar.

**Fluxo de dados:**
1. Preview carrega → obtém `startDate` e `endDate`
2. Segunda chamada: `GET /api/transactions?startDate=X&endDate=Y` (sem outros filtros)
3. Agrega client-side: filtra `status !== 'CANCELLED'`, depois conta e soma por tipo (EXPENSE / INCOME)

**Renderização:**
- Se count = 0: nada é exibido
- Se count > 0: linha de aviso com ícone `info` em tom `on-surface-variant`:
  > *"N transações já lançadas neste período · R$ X em despesas · R$ Y em receitas"*
- Tom informativo (não alarmante) — sem botão, sem ação

**Estado de carregamento:** sinal `loadingTransactions` separado do `loadingPreview`. O dialog exibe os itens do preview assim que o preview carrega; a linha de transações aparece quando essa segunda chamada termina (ou some silenciosamente se der erro).

---

## Seção 5 — Saldo Projetado

**Atual:** `totals-row` com dois spans: "Receita planejada: R$ X" e "Despesa planejada: R$ Y".

**Proposto:** substituir por equação visual com resultado em destaque:

```
Abertura      Receita prevista    Despesa prevista    Saldo projetado
R$ 4.200      + R$ 6.500          − R$ 4.300          = R$ 6.400
```

- Quatro colunas em `display: grid`, alinhadas
- "Saldo projetado" em `font-size: 1.1rem`, `font-weight: 600`
- Verde (`--mat-sys-primary`) se positivo, vermelho (`--mat-sys-error`) se negativo
- Calculado como `computed()`: `openingBalanceSignal() + projectedIncome - projectedExpense`
- Atualiza em tempo real conforme o usuário edita o campo de abertura

---

## Arquitetura do Componente

### Sinais novos

```typescript
readonly loadingTransactions = signal(false);
readonly existingTransactions = signal<{ count: number; expense: number; income: number } | null>(null);
readonly openingBalanceValue = toSignal(this.form.controls.openingBalance.valueChanges, {
  initialValue: this.form.controls.openingBalance.value,
});
readonly projectedBalance = computed(() => {
  const p = this.preview();
  const ob = this.openingBalanceValue() ?? 0;
  if (!p) return null;
  return ob + (p.projectedIncome ?? 0) - (p.projectedExpense ?? 0);
});
```

### Fluxo de inicialização

```
ngOnInit
  └─ previewCycle()
        ├─ next: set preview, patch openingBalance
        └─ then: transactionService.list({ startDate, endDate })
                    ├─ next: aggregate e set existingTransactions
                    └─ error: silencioso (não bloqueia o dialog)
```

### Serviço de transações

Usa o `TransactionsService` gerado pelo Orval (já injetado em outras features). Sem novo serviço.

---

## Arquivos Alterados

| Arquivo | Mudança |
|---|---|
| `budget-cycle-open-dialog.html` | Refactor visual completo das 5 seções |
| `budget-cycle-open-dialog.ts` | Adicionar sinais, segunda chamada API, computed projectedBalance |
| `budget-cycle-open-dialog.scss` | Estilos do cabeçalho, equação de saldo, linha de transações |

Nenhum arquivo novo. Nenhuma mudança de backend.

---

## Fora do Escopo

- Editar o período antes de abrir (o `startDay` já vem do perfil do tenant)
- Listar individualmente as transações existentes (resumo é suficiente)
- Mudanças na tela de ciclo atual (`budget-cycle-current`)
