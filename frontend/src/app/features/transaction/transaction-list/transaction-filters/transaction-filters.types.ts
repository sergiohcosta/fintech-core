export interface TransactionFilters {
  accountIds: string[];
  status: 'PENDING' | 'PAID' | 'CANCELLED' | null;
  type: 'INCOME' | 'EXPENSE' | null;
  startDate: string | null;  // 'YYYY-MM-DD'
  endDate: string | null;    // 'YYYY-MM-DD'
  groupByPeriod: boolean;
}

export const DEFAULT_FILTERS: TransactionFilters = {
  accountIds: [],
  status: null,
  type: null,
  startDate: null,
  endDate: null,
  groupByPeriod: false,
};
