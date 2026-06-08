export interface TransactionFilters {
  accountId: string | null;
  status: 'PENDING' | 'PAID' | 'CANCELLED' | null;
  type: 'INCOME' | 'EXPENSE' | null;
  startDate: string | null;  // 'YYYY-MM-DD'
  endDate: string | null;    // 'YYYY-MM-DD'
  groupByPeriod: boolean;
}

export const DEFAULT_FILTERS: TransactionFilters = {
  accountId: null,
  status: null,
  type: null,
  startDate: null,
  endDate: null,
  groupByPeriod: false,
};
