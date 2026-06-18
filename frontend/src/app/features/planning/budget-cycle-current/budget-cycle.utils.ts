import { BudgetCycleSummary } from '../../../core/api/fintechSaaSAPI.schemas';

export const DEFAULT_SUMMARY: Required<BudgetCycleSummary> = {
  plannedIncome:    0,
  plannedExpense:   0,
  projectedBalance: 0,
  realizedIncome:   0,
  realizedExpense:  0,
  currentBalance:   0,
  pendingCount:     0,
  unplannedIncome:  0,
  unplannedExpense: 0,
  availableToSpend: 0,
  dailyAllowance:   null,
  remainingDays:    null,
};
