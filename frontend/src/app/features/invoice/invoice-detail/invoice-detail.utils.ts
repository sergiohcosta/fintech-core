import { TransactionResponseDTO, TransactionStatus } from '../../../core/api/fintechSaaSAPI.schemas';

export interface CategoryBreakdownRow {
  categoryPath: string;
  categoryIcon: string | null;
  count: number;
  total: number;
  percentage: number;
}

export function computeBreakdown(
  transactions: TransactionResponseDTO[],
  totalExpense: number
): CategoryBreakdownRow[] {
  const active = transactions.filter(t => t.status !== TransactionStatus.CANCELLED);
  const map = new Map<string, { icon: string | null; count: number; total: number }>();

  for (const t of active) {
    const key = t.categoryPath ?? t.categoryName ?? 'Sem categoria';
    const icon = t.categoryIcon ?? null;
    const curr = map.get(key) ?? { icon, count: 0, total: 0 };
    map.set(key, { icon: curr.icon ?? icon, count: curr.count + 1, total: curr.total + t.amount });
  }

  return Array.from(map.entries())
    .map(([categoryPath, { icon, count, total }]) => ({
      categoryPath,
      categoryIcon: icon,
      count,
      total,
      percentage: totalExpense > 0 ? (Math.abs(total) / totalExpense) * 100 : 0
    }))
    .sort((a, b) => Math.abs(b.total) - Math.abs(a.total));
}
