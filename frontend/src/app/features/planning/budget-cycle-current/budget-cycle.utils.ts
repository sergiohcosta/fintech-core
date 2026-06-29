import { BudgetCycleSummary, RecurrenceRuleResponseDTO, TransactionType } from '../../../core/api/fintechSaaSAPI.schemas';

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

// Procura um recorrente ATIVO com mesma descrição (case-insensitive, sem espaços nas bordas)
// e mesmo tipo. Usado para alertar sobre duplicação ao "tornar recorrente".
// Recorrentes cancelados (status !== 'ACTIVE') não contam como duplicata.
export function findDuplicateRecurring(
  existing: RecurrenceRuleResponseDTO[],
  description: string,
  type: TransactionType,
): RecurrenceRuleResponseDTO | undefined {
  const desc = description.trim().toLowerCase();
  return existing.find(
    r => r.status === 'ACTIVE'
      && r.type === type
      && (r.description ?? '').trim().toLowerCase() === desc,
  );
}
