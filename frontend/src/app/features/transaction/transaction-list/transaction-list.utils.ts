import { TransactionResponseDTO, InvoiceStatus } from '../../../core/api/fintechSaaSAPI.schemas';

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

export type InvoiceHeaderRow = {
  kind: 'invoice-header';
  invoiceId: string | null;
  label: string;
  dueDate: string | null;
  totalAmount: number;
  status: InvoiceStatus | null;
  transactionCount: number;
};

export type DisplayRow =
  | { kind: 'single';             data: TransactionResponseDTO }
  | { kind: 'installment';        data: TransactionResponseDTO; group: InstallmentGroupInfo; isExpanded: boolean }
  | { kind: 'installment-detail'; data: TransactionResponseDTO; group: InstallmentGroupInfo }
  | { kind: 'period-header';      key: string; label: string; totalIncome: number; totalExpense: number; balance: number }
  | InvoiceHeaderRow;

function sortTransferPairsTogether(transactions: TransactionResponseDTO[]): TransactionResponseDTO[] {
  const result: TransactionResponseDTO[] = [];
  const placed = new Set<string>();
  for (const t of transactions) {
    if (placed.has(t.id)) continue;
    placed.add(t.id);
    result.push(t);
    if (t.transferId) {
      const pair = transactions.find(
        other => other.transferId === t.transferId && other.id !== t.id && !placed.has(other.id)
      );
      if (pair) {
        placed.add(pair.id);
        result.push(pair);
      }
    }
  }
  return result;
}

function buildFlatRows(
  transactions: TransactionResponseDTO[],
  expandedIds: Set<string>
): DisplayRow[] {
  transactions = sortTransferPairsTogether(transactions);
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

function buildDisplayRowsGroupedByInvoice(
  transactions: TransactionResponseDTO[],
  expandedIds: Set<string>
): DisplayRow[] {
  const withInvoice    = transactions.filter(t => t.invoiceId);
  const withoutInvoice = transactions.filter(t => !t.invoiceId);

  type InvoiceBucket = { dueDate: string | null; status: InvoiceStatus | null; label: string; transactions: TransactionResponseDTO[] };
  const invoiceMap = new Map<string, InvoiceBucket>();

  for (const t of withInvoice) {
    const id = t.invoiceId!;
    if (!invoiceMap.has(id)) {
      const label = t.invoiceDueDate
        ? 'Fatura ' + new Date(t.invoiceDueDate + 'T00:00:00').toLocaleDateString('pt-BR', { month: 'short', year: 'numeric' })
        : 'Fatura';
      invoiceMap.set(id, { dueDate: t.invoiceDueDate ?? null, status: t.invoiceStatus ?? null, label, transactions: [] });
    }
    invoiceMap.get(id)!.transactions.push(t);
  }

  const sorted = [...invoiceMap.entries()].sort(([, a], [, b]) => {
    if (!a.dueDate) return 1;
    if (!b.dueDate) return -1;
    return b.dueDate.localeCompare(a.dueDate);
  });

  const calcTotal = (txs: TransactionResponseDTO[]) =>
    txs.reduce((sum, t) => t.type === 'EXPENSE' ? sum + (t.amount ?? 0) : t.type === 'INCOME' ? sum - (t.amount ?? 0) : sum, 0);

  const rows: DisplayRow[] = [];

  for (const [invoiceId, bucket] of sorted) {
    rows.push({
      kind: 'invoice-header',
      invoiceId,
      label: bucket.label,
      dueDate: bucket.dueDate,
      totalAmount: calcTotal(bucket.transactions),
      status: bucket.status,
      transactionCount: bucket.transactions.length,
    });
    rows.push(...buildFlatRows(bucket.transactions, expandedIds));
  }

  if (withoutInvoice.length > 0) {
    rows.push({
      kind: 'invoice-header',
      invoiceId: null,
      label: 'Avulsas',
      dueDate: null,
      totalAmount: calcTotal(withoutInvoice),
      status: null,
      transactionCount: withoutInvoice.length,
    });
    rows.push(...buildFlatRows(withoutInvoice, expandedIds));
  }

  return rows;
}

export function buildDisplayRows(
  transactions: TransactionResponseDTO[],
  expandedIds: Set<string>,
  groupByPeriod = false,
  groupByInvoice = false
): DisplayRow[] {
  if (groupByInvoice) return buildDisplayRowsGroupedByInvoice(transactions, expandedIds);
  if (!groupByPeriod) return buildFlatRows(transactions, expandedIds);
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
    .map(([key, txs]) => {
      const [totalIncome, totalExpense] = txs.reduce(
        ([inc, exp], t) => {
          if (t.type === 'INCOME')  return [inc + (t.amount ?? 0), exp];
          if (t.type === 'EXPENSE') return [inc, exp + (t.amount ?? 0)];
          return [inc, exp];
        },
        [0, 0] as [number, number]
      );
      return {
        key,
        label: formatMonthLabel(key),
        transactions: txs,
        totalIncome,
        totalExpense,
        balance: totalIncome - totalExpense,
        isCurrentMonth: key === currentKey,
      };
    });
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

export type MonthChipState = {
  label: string;
  key: string;
  active: boolean;
  disabled: boolean;
};

const MONTH_LABELS = ['Jan','Fev','Mar','Abr','Mai','Jun','Jul','Ago','Set','Out','Nov','Dez'];

export function computeMonthChipStates(
  year: number,
  nowMonth: number,
  startDate: string | null,
  endDate: string | null
): MonthChipState[] {
  const activeKey = resolveMonthKey(startDate, endDate);
  return Array.from({ length: 12 }, (_, i) => {
    const monthNum = i + 1;
    const key = `${year}-${String(monthNum).padStart(2, '0')}`;
    return {
      label: MONTH_LABELS[i],
      key,
      active: activeKey === key,
      disabled: monthNum > nowMonth,
    };
  });
}
