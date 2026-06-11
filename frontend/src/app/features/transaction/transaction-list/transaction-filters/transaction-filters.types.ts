export interface TransactionFilters {
  accountIds: string[];
  status: 'PENDING' | 'PAID' | 'CANCELLED' | null;
  type: 'INCOME' | 'EXPENSE' | null;
  startDate: string | null;
  endDate: string | null;
  groupByPeriod: boolean;
  groupByInvoice: boolean;
  description: string | null;
}

export const DEFAULT_FILTERS: TransactionFilters = {
  accountIds: [],
  status: null,
  type: null,
  startDate: null,
  endDate: null,
  groupByPeriod: false,
  groupByInvoice: false,
  description: null,
};

export function currentMonthFilters(): TransactionFilters {
  const now = new Date();
  const year = now.getFullYear();
  const month = now.getMonth() + 1;
  const pad = (n: number) => String(n).padStart(2, '0');
  const lastDay = new Date(year, month, 0).getDate();
  return {
    ...DEFAULT_FILTERS,
    startDate: `${year}-${pad(month)}-01`,
    endDate:   `${year}-${pad(month)}-${pad(lastDay)}`,
  };
}

export function currentMonthKey(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
}
