import { TransactionResponseDTO, TransactionStatus } from '../../../core/api/fintechSaaSAPI.schemas';
import { csvField } from '../../../core/csv.utils';

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

const TYPE_LABEL: Record<string, string> = { INCOME: 'Receita', EXPENSE: 'Despesa' };
const STATUS_LABEL: Record<string, string> = { PAID: 'Pago', PENDING: 'Pendente', CANCELLED: 'Cancelado' };

export function exportInvoiceToCsv(transactions: TransactionResponseDTO[]): string {
  const header = 'Data;Descrição;Valor;Tipo;Status;Categoria;Parcela';

  const rows = transactions.map(t => {
    const [y, m, d] = (t.date ?? '').split('-');
    const data      = d && m && y ? `${d}/${m}/${y}` : '';
    const valor     = (t.amount ?? 0).toFixed(2).replace('.', ',');
    const tipo      = TYPE_LABEL[t.type ?? ''] ?? t.type ?? '';
    const status    = STATUS_LABEL[t.status ?? ''] ?? t.status ?? '';
    const categoria = csvField(t.categoryPath ?? t.categoryName ?? '');
    const parcela   = t.installmentLabel ?? '';

    return [csvField(data), csvField(t.description), valor, tipo, status, categoria, parcela].join(';');
  });

  return [header, ...rows].join('\n');
}
