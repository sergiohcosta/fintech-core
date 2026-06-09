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
