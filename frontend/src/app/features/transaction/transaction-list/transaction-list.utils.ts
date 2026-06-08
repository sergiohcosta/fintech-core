import { TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

export type InstallmentGroupInfo = {
  groupId: string;
  description: string;
  totalInstallments: number;
  paidInstallments: number;
  installmentAmount: number;
  categoryName: string | null;
  accountName: string | null;
  transactions: TransactionResponseDTO[];
};

export type PeriodGroup = {
  key: string;
  label: string;
  transactions: TransactionResponseDTO[];
  totalIncome: number;
  totalExpense: number;
  balance: number;
  isCurrentMonth: boolean;
};

export type DisplayRow =
  | { kind: 'single';             data: TransactionResponseDTO }
  | { kind: 'installment';        data: TransactionResponseDTO; group: InstallmentGroupInfo; isExpanded: boolean }
  | { kind: 'installment-detail'; data: TransactionResponseDTO; group: InstallmentGroupInfo }
  | { kind: 'period-header';      key: string; label: string; totalIncome: number; totalExpense: number; balance: number };

function buildFlatRows(
  transactions: TransactionResponseDTO[],
  expandedIds: Set<string>
): DisplayRow[] {
  const groupsMap = new Map<string, TransactionResponseDTO[]>();
  for (const t of transactions) {
    if (t.installmentGroupId) {
      const existing = groupsMap.get(t.installmentGroupId) ?? [];
      existing.push(t);
      groupsMap.set(t.installmentGroupId, existing);
    }
  }

  const groupInfoMap = new Map<string, InstallmentGroupInfo>();
  for (const [groupId, txs] of groupsMap) {
    groupInfoMap.set(groupId, {
      groupId,
      description: txs[0]?.installmentGroupDescription ?? txs[0]?.description ?? '',
      totalInstallments: txs.length,
      paidInstallments: txs.filter(tx => tx.status === 'PAID').length,
      installmentAmount: txs[0]?.amount ?? 0,
      categoryName: txs[0]?.categoryName ?? null,
      accountName: txs[0]?.accountName ?? null,
      transactions: txs,
    });
  }

  return transactions.flatMap(t => {
    if (t.installmentGroupId) {
      const group = groupInfoMap.get(t.installmentGroupId)!;
      const isExpanded = expandedIds.has(t.id);
      const rows: DisplayRow[] = [{ kind: 'installment', data: t, group, isExpanded }];
      if (isExpanded) {
        rows.push({ kind: 'installment-detail', data: t, group });
      }
      return rows;
    }
    return [{ kind: 'single', data: t }];
  });
}

export function buildDisplayRows(
  transactions: TransactionResponseDTO[],
  expandedIds: Set<string>,
  groupByPeriod = false
): DisplayRow[] {
  if (!groupByPeriod) {
    return buildFlatRows(transactions, expandedIds);
  }
  const groups = groupByEffectiveMonth(transactions);
  return groups.flatMap(group => [
    {
      kind: 'period-header' as const,
      key: group.key,
      label: group.label,
      totalIncome: group.totalIncome,
      totalExpense: group.totalExpense,
      balance: group.balance,
    },
    ...buildFlatRows(group.transactions, expandedIds),
  ]);
}

export function effectiveMonth(t: TransactionResponseDTO): string {
  if (t.installmentGroupId && t.invoiceDueDate) {
    return t.invoiceDueDate.substring(0, 7);
  }
  return (t.date ?? '').substring(0, 7);
}

export function formatMonthLabel(key: string): string {
  const [year, month] = key.split('-').map(Number);
  const date = new Date(year, month - 1, 1);
  const label = date.toLocaleDateString('pt-BR', { month: 'long', year: 'numeric' });
  return label.charAt(0).toUpperCase() + label.slice(1);
}

export function groupByEffectiveMonth(transactions: TransactionResponseDTO[]): PeriodGroup[] {
  const now = new Date();
  const currentKey = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;

  const map = new Map<string, TransactionResponseDTO[]>();
  for (const t of transactions) {
    const key = effectiveMonth(t);
    if (!key) continue;
    const bucket = map.get(key) ?? [];
    bucket.push(t);
    map.set(key, bucket);
  }

  return [...map.entries()]
    .sort(([a], [b]) => b.localeCompare(a))
    .map(([key, txs]) => ({
      key,
      label: formatMonthLabel(key),
      transactions: txs,
      totalIncome:  txs.filter(t => t.type === 'INCOME').reduce((s, t) => s + (t.amount ?? 0), 0),
      totalExpense: txs.filter(t => t.type === 'EXPENSE').reduce((s, t) => s + (t.amount ?? 0), 0),
      balance:      txs.filter(t => t.type === 'INCOME').reduce((s, t) => s + (t.amount ?? 0), 0)
                  - txs.filter(t => t.type === 'EXPENSE').reduce((s, t) => s + (t.amount ?? 0), 0),
      isCurrentMonth: key === currentKey,
    }));
}

export function resolveMonthKey(startDate: string | null, endDate: string | null): string {
  if (!startDate || !endDate) return '';
  const [sy, sm] = startDate.split('-').map(Number);
  const [ey, em] = endDate.split('-').map(Number);
  const firstDay = new Date(sy, sm - 1, 1);
  const lastDay  = new Date(sy, sm, 0);
  const first = `${firstDay.getFullYear()}-${String(firstDay.getMonth() + 1).padStart(2, '0')}-${String(firstDay.getDate()).padStart(2, '0')}`;
  const last  = `${lastDay.getFullYear()}-${String(lastDay.getMonth() + 1).padStart(2, '0')}-${String(lastDay.getDate()).padStart(2, '0')}`;
  if (first === startDate && last === endDate && sy === ey && sm === em) {
    return `${sy}-${String(sm).padStart(2, '0')}`;
  }
  return 'custom';
}

export function monthBounds(key: string): { startDate: string; endDate: string } {
  const [year, month] = key.split('-').map(Number);
  const firstDay = new Date(year, month - 1, 1);
  const lastDay  = new Date(year, month, 0);
  const pad = (n: number) => String(n).padStart(2, '0');
  return {
    startDate: `${firstDay.getFullYear()}-${pad(firstDay.getMonth() + 1)}-${pad(firstDay.getDate())}`,
    endDate:   `${lastDay.getFullYear()}-${pad(lastDay.getMonth() + 1)}-${pad(lastDay.getDate())}`,
  };
}
