import {
  BudgetCycleSummary,
  BudgetItemResponse,
  TransactionResponseDTO,
} from '../../../core/api/fintechSaaSAPI.schemas';

// Composição (breakdown) de cada card do resumo do ciclo. Lógica pura, sem Angular,
// para ser testável no Vitest sem TestBed — mesmo padrão de budget-cycle.utils.ts.
// Os TOTAIS de seção e os resultados vêm do summary() (autoritativo, calculado no backend);
// a listagem de contribuintes é montada a partir dos itens e avulsas que o componente já tem.

export type BreakdownCard = 'income' | 'expense' | 'balance' | 'available';

export interface BreakdownEntry {
  label: string;
  detail: string;
  amount: number;
}

export interface BreakdownSection {
  title: string;
  total: number;
  entries: BreakdownEntry[];
}

export interface BreakdownResult {
  label: string;
  amount: number;
  note?: string;
  emphasis?: boolean;
}

export interface CardBreakdown {
  title: string;
  sections: BreakdownSection[];
  results: BreakdownResult[];
}

export interface BreakdownContext {
  openingBalance: number;
  summary: Required<BudgetCycleSummary>;
  items: BudgetItemResponse[];
  unplanned: TransactionResponseDTO[];
}

const round2 = (n: number): number => Math.round(n * 100) / 100;
const sum = (ns: number[]): number => round2(ns.reduce((acc, n) => acc + n, 0));

const itemDetail = (status: BudgetItemResponse['status']): string =>
  status === 'REALIZED' ? 'realizado' : status === 'SKIPPED' ? 'ignorado' : 'previsto';

const txDetail = (status: TransactionResponseDTO['status']): string =>
  status === 'PAID' ? 'avulsa · paga' : 'avulsa · pendente';

const itemEntry = (i: BudgetItemResponse): BreakdownEntry => ({
  label: i.description ?? '—',
  detail: itemDetail(i.status),
  amount: i.amount ?? 0,
});

const txEntry = (t: TransactionResponseDTO): BreakdownEntry => ({
  label: t.description,
  detail: txDetail(t.status),
  amount: t.amount,
});

export function buildBreakdown(card: BreakdownCard, ctx: BreakdownContext): CardBreakdown {
  switch (card) {
    case 'income':    return sideBreakdown(ctx, 'INCOME');
    case 'expense':   return sideBreakdown(ctx, 'EXPENSE');
    case 'balance':   return balanceBreakdown(ctx);
    case 'available': return availableBreakdown(ctx);
  }
}

// Cards Receitas/Despesas: listam os itens planejados (exceto SKIPPED) e mostram
// Previsto (todos os ativos) e Realizado (em caixa) como resultados.
function sideBreakdown(ctx: BreakdownContext, type: 'INCOME' | 'EXPENSE'): CardBreakdown {
  const income = type === 'INCOME';
  const active = ctx.items.filter(i => i.type === type && i.status !== 'SKIPPED');
  const planned  = income ? ctx.summary.plannedIncome  : ctx.summary.plannedExpense;
  const realized = income ? ctx.summary.realizedIncome : ctx.summary.realizedExpense;

  return {
    title: income ? 'Receitas — composição' : 'Despesas — composição',
    sections: [{
      title: income ? 'Itens de receita' : 'Itens de despesa',
      total: planned,
      entries: active.map(itemEntry),
    }],
    results: [
      { label: 'Previsto', amount: planned, emphasis: true },
      { label: 'Realizado (em caixa)', amount: realized, note: 'itens realizados e pagos' },
    ],
  };
}

// Card Saldo: o "Atual" (currentBalance) é caixa real — só entradas/saídas PAGAS.
// Itens realizados são proxy de "pago" (ver ponytail abaixo).
function balanceBreakdown(ctx: BreakdownContext): CardBreakdown {
  // ponytail: usa item.status === 'REALIZED' como proxy de "transação paga". O DTO do item
  // não expõe o status da transação vinculada; só diverge no caso raro de REALIZED ligado a
  // transação PENDING. O total "Atual" abaixo vem do summary (exato). Upgrade: expor
  // transactionStatus em BudgetItemResponse se a fidelidade nesse caso de borda for necessária.
  const realizedIncome  = ctx.items.filter(i => i.type === 'INCOME'  && i.status === 'REALIZED');
  const realizedExpense = ctx.items.filter(i => i.type === 'EXPENSE' && i.status === 'REALIZED');
  const unplIncomePaid  = ctx.unplanned.filter(t => t.type === 'INCOME'  && t.status === 'PAID');
  const unplExpensePaid = ctx.unplanned.filter(t => t.type === 'EXPENSE' && t.status === 'PAID');

  const incomeEntries  = [...realizedIncome.map(itemEntry),  ...unplIncomePaid.map(txEntry)];
  const expenseEntries = [...realizedExpense.map(itemEntry), ...unplExpensePaid.map(txEntry)];

  return {
    title: 'Saldo — composição',
    sections: [
      { title: 'Entradas em caixa (pagas)', total: sum(incomeEntries.map(e => e.amount)), entries: incomeEntries },
      { title: 'Saídas em caixa (pagas)',   total: sum(expenseEntries.map(e => e.amount)), entries: expenseEntries },
    ],
    results: [
      { label: 'Abertura',  amount: ctx.openingBalance, note: 'caixa antes do ciclo' },
      { label: 'Projetado', amount: ctx.summary.projectedBalance, note: 'abertura + previsto receitas − previsto despesas' },
      { label: 'Atual (em caixa)', amount: ctx.summary.currentBalance, note: 'abertura + entradas pagas − saídas pagas', emphasis: true },
    ],
  };
}

// Card Disponível: availableToSpend conta TUDO que está lançado (exceto SKIPPED),
// independente de pago/pendente. Itemização exata (não precisa de proxy).
function availableBreakdown(ctx: BreakdownContext): CardBreakdown {
  const activeIncome  = ctx.items.filter(i => i.type === 'INCOME'  && i.status !== 'SKIPPED');
  const activeExpense = ctx.items.filter(i => i.type === 'EXPENSE' && i.status !== 'SKIPPED');
  const unplIncome  = ctx.unplanned.filter(t => t.type === 'INCOME');
  const unplExpense = ctx.unplanned.filter(t => t.type === 'EXPENSE');

  const results: BreakdownResult[] = [
    { label: 'Abertura', amount: ctx.openingBalance },
    { label: 'Para gastar', amount: ctx.summary.availableToSpend, note: 'abertura + todas receitas − todas despesas', emphasis: true },
  ];
  if (ctx.summary.dailyAllowance != null) {
    results.push({
      label: 'Por dia',
      amount: ctx.summary.dailyAllowance,
      note: ctx.summary.remainingDays != null ? `÷ ${ctx.summary.remainingDays} dias restantes` : undefined,
    });
  }

  return {
    title: 'Disponível — composição',
    sections: [
      {
        title: 'Receitas (todas)',
        total: round2(ctx.summary.plannedIncome + ctx.summary.unplannedIncome),
        entries: [...activeIncome.map(itemEntry), ...unplIncome.map(txEntry)],
      },
      {
        title: 'Despesas (todas)',
        total: round2(ctx.summary.plannedExpense + ctx.summary.unplannedExpense),
        entries: [...activeExpense.map(itemEntry), ...unplExpense.map(txEntry)],
      },
    ],
    results,
  };
}
