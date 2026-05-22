export interface DashboardSummary {
  period: string; // "yyyy-MM" — serializado como string pelo Jackson
  totalIncome: number;
  totalExpense: number;
  balance: number;
}
