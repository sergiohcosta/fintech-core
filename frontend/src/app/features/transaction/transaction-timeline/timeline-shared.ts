import { TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

// Mesma regra do backend (TransactionService.effectiveSortDate): parcela de cartão
// referencia o vencimento da fatura; o resto referencia a data da própria transação.
export function effectiveSortDate(t: TransactionResponseDTO): string {
  if (t.installmentGroupId && t.invoiceDueDate) return t.invoiceDueDate;
  return t.date!;
}

export interface DayTotals {
  income: number;
  expense: number;
  net: number;
}

export function dayTotals(txs: TransactionResponseDTO[]): DayTotals {
  let income = 0;
  let expense = 0;
  for (const t of txs) {
    if (t.status === 'CANCELLED') continue;
    if (t.type === 'INCOME') income += t.amount ?? 0;
    else if (t.type === 'EXPENSE') expense += t.amount ?? 0;
  }
  return { income, expense, net: income - expense };
}
