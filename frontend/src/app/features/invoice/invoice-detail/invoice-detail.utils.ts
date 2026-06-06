import { TransactionResponseDTO, TransactionStatus } from '../../../core/api/fintechSaaSAPI.schemas';

export interface CategoryBreakdownRow {
  categoryName: string;
  count: number;
  total: number;
  percentage: number;
}

export function computeBreakdown(
  transactions: TransactionResponseDTO[],
  totalExpense: number
): CategoryBreakdownRow[] {
  const active = transactions.filter(t => t.status !== TransactionStatus.CANCELLED);
  const map = new Map<string, { count: number; total: number }>();

  for (const t of active) {
    const key = t.categoryName ?? 'Sem categoria';
    const curr = map.get(key) ?? { count: 0, total: 0 };
    map.set(key, { count: curr.count + 1, total: curr.total + t.amount });
  }

  return Array.from(map.entries())
    .map(([categoryName, { count, total }]) => ({
      categoryName,
      count,
      total,
      percentage: totalExpense > 0 ? (Math.abs(total) / totalExpense) * 100 : 0
    }))
    .sort((a, b) => Math.abs(b.total) - Math.abs(a.total));
}
