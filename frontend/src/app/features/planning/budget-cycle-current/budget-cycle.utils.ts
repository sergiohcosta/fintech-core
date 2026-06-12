import { BudgetItemResponse } from '../../../core/api/fintechSaaSAPI.schemas';

export interface CycleSummary {
  plannedIncome: number;
  plannedExpense: number;
  projectedBalance: number;
  realizedIncome: number;
  realizedExpense: number;
  currentBalance: number;
  pendingCount: number;
}

export function buildSummary(items: BudgetItemResponse[], openingBalance: number): CycleSummary {
  let plannedIncome = 0, plannedExpense = 0;
  let realizedIncome = 0, realizedExpense = 0;
  let pendingCount = 0;

  for (const item of items) {
    const isIncome = item.type === 'INCOME';

    if (isIncome) plannedIncome  += item.amount ?? 0;
    else          plannedExpense += item.amount ?? 0;

    if (item.status === 'REALIZED') {
      if (isIncome) realizedIncome  += item.amount ?? 0;
      else          realizedExpense += item.amount ?? 0;
    }

    if (item.status === 'PENDING') pendingCount++;
  }

  return {
    plannedIncome,
    plannedExpense,
    projectedBalance: openingBalance + plannedIncome - plannedExpense,
    realizedIncome,
    realizedExpense,
    currentBalance: openingBalance + realizedIncome - realizedExpense,
    pendingCount,
  };
}
