import { TransactionResponseDTO, InvoiceResponseDTO, InvoiceStatus } from '../../../core/api/fintechSaaSAPI.schemas';

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

export type InvoiceSummaryRow = {
  kind: 'invoice-summary';
  invoiceId: string;
  label: string;
  dueDate: string;
  totalAmount: number;
  status: InvoiceStatus;
  accountName: string;
  transactionCount: number;
};

export type DisplayRow =
  | { kind: 'single';             data: TransactionResponseDTO }
  | { kind: 'installment';        data: TransactionResponseDTO; group: InstallmentGroupInfo; isExpanded: boolean }
  | { kind: 'installment-detail'; data: TransactionResponseDTO; group: InstallmentGroupInfo }
  | { kind: 'period-header';      key: string; label: string; totalIncome: number; totalExpense: number; balance: number }
  | InvoiceSummaryRow;

// "Linha fantasma": projeção de uma regra de recorrência, ainda não materializada.
export const isGhost = (r: { projected?: boolean }): boolean => r.projected === true;

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
  expandedIds: Set<string>,
  sortCriteria: SortCriterion[] = [],
  invoices: InvoiceResponseDTO[] = []
): DisplayRow[] {
  const withoutInvoice = transactions.filter(t => !t.invoiceId);

  let summaryRows: DisplayRow[];

  if (invoices.length > 0) {
    // ponytail: totais reais vindos da API — sem recalcular a partir das transações
    const sorted = [...invoices].sort((a, b) => b.dueDate.localeCompare(a.dueDate));
    summaryRows = sorted.map(inv => ({
      kind: 'invoice-summary' as const,
      invoiceId: inv.id,
      label: inv.label,
      dueDate: inv.dueDate,
      totalAmount: inv.totalAmount,
      status: inv.status,
      accountName: inv.accountName,
      transactionCount: inv.transactionCount,
    }));
  } else {
    // fallback: agrupa a partir das transações disponíveis (sem filtro de período ativo)
    const withInvoice = transactions.filter(t => t.invoiceId);
    type Bucket = { dueDate: string; status: InvoiceStatus; label: string; accountName: string; txs: TransactionResponseDTO[] };
    const map = new Map<string, Bucket>();
    for (const t of withInvoice) {
      const id = t.invoiceId!;
      if (!map.has(id)) {
        const label = t.invoiceDueDate
          ? 'Fatura ' + new Date(t.invoiceDueDate + 'T00:00:00').toLocaleDateString('pt-BR', { month: 'short', year: 'numeric' })
          : 'Fatura';
        map.set(id, { dueDate: t.invoiceDueDate ?? '', status: t.invoiceStatus ?? 'OPEN', label, accountName: t.accountName ?? '', txs: [] });
      }
      map.get(id)!.txs.push(t);
    }
    const calcTotal = (txs: TransactionResponseDTO[]) =>
      txs.reduce((s, t) => t.type === 'EXPENSE' ? s + (t.amount ?? 0) : t.type === 'INCOME' ? s - (t.amount ?? 0) : s, 0);
    summaryRows = [...map.entries()]
      .sort(([, a], [, b]) => b.dueDate.localeCompare(a.dueDate))
      .map(([invoiceId, b]) => ({
        kind: 'invoice-summary' as const,
        invoiceId, label: b.label, dueDate: b.dueDate,
        totalAmount: calcTotal(b.txs), status: b.status,
        accountName: b.accountName, transactionCount: b.txs.length,
      }));
  }

  return [...summaryRows, ...buildFlatRows(sortTransactions(withoutInvoice, sortCriteria), expandedIds)];
}

export function buildDisplayRows(
  transactions: TransactionResponseDTO[],
  expandedIds: Set<string>,
  groupByPeriod = false,
  groupByInvoice = false,
  sortCriteria: SortCriterion[] = [],
  invoices: InvoiceResponseDTO[] = []
): DisplayRow[] {
  if (groupByInvoice) return buildDisplayRowsGroupedByInvoice(transactions, expandedIds, sortCriteria, invoices);
  if (!groupByPeriod) return buildFlatRows(sortTransactions(transactions, sortCriteria), expandedIds);
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
    ...buildFlatRows(sortTransactions(group.transactions, sortCriteria), expandedIds),
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

// --- Multi-sort ---

export type SortCol = 'description' | 'amount' | 'date' | 'type' | 'status' | 'category' | 'account';
export type SortDir = 'asc' | 'desc';
export type SortCriterion = { col: SortCol; dir: SortDir };

function effectiveSortDate(t: TransactionResponseDTO): string {
  if (t.installmentGroupId && t.invoiceDueDate) return t.invoiceDueDate;
  return t.date ?? '';
}

function compareBy(col: SortCol, a: TransactionResponseDTO, b: TransactionResponseDTO): number {
  switch (col) {
    case 'date':        return effectiveSortDate(a).localeCompare(effectiveSortDate(b));
    case 'amount':      return (a.amount ?? 0) - (b.amount ?? 0);
    case 'description': return (a.description ?? '').localeCompare(b.description ?? '', 'pt-BR', { sensitivity: 'base' });
    case 'category': {
      const ac = a.categoryName ?? null, bc = b.categoryName ?? null;
      if (!ac && !bc) return 0; if (!ac) return 1; if (!bc) return -1;
      return ac.localeCompare(bc, 'pt-BR', { sensitivity: 'base' });
    }
    case 'account': {
      const aa = a.accountName ?? null, ba = b.accountName ?? null;
      if (!aa && !ba) return 0; if (!aa) return 1; if (!ba) return -1;
      return aa.localeCompare(ba, 'pt-BR', { sensitivity: 'base' });
    }
    case 'type': {
      const order: Record<string, number> = { INCOME: 0, EXPENSE: 1 };
      return (a.transferId ? 2 : (order[a.type ?? ''] ?? 1)) - (b.transferId ? 2 : (order[b.type ?? ''] ?? 1));
    }
    case 'status': {
      const order: Record<string, number> = { PENDING: 0, PAID: 1, CANCELLED: 2 };
      return (order[a.status ?? ''] ?? 0) - (order[b.status ?? ''] ?? 0);
    }
  }
}

export function sortTransactions(
  transactions: TransactionResponseDTO[],
  criteria: SortCriterion[]
): TransactionResponseDTO[] {
  if (!criteria.length) return transactions;
  return [...transactions].sort((a, b) => {
    for (const { col, dir } of criteria) {
      const cmp = compareBy(col, a, b);
      if (cmp !== 0) return dir === 'asc' ? cmp : -cmp;
    }
    return (b.createdAt ?? '').localeCompare(a.createdAt ?? '');
  });
}

export function applySort(criteria: SortCriterion[], col: SortCol, shiftKey: boolean): SortCriterion[] {
  if (shiftKey) {
    const idx = criteria.findIndex(c => c.col === col);
    if (idx >= 0) {
      const updated = [...criteria];
      updated[idx] = { col, dir: criteria[idx].dir === 'asc' ? 'desc' : 'asc' };
      return updated;
    }
    return [...criteria, { col, dir: 'asc' }];
  }
  if (criteria.length === 1 && criteria[0].col === col) {
    return [{ col, dir: criteria[0].dir === 'asc' ? 'desc' : 'asc' }];
  }
  return [{ col, dir: 'asc' }];
}

export function getSortInfo(
  criteria: SortCriterion[],
  col: SortCol
): { priority: number; dir: SortDir } | null {
  const idx = criteria.findIndex(c => c.col === col);
  if (idx < 0) return null;
  return { priority: idx + 1, dir: criteria[idx].dir };
}
