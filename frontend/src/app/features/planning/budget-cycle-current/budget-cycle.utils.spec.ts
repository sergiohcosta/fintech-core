import { describe, it, expect } from 'vitest';
import { DEFAULT_SUMMARY, findDuplicateRecurring } from './budget-cycle.utils';
import { RecurrenceRuleResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

describe('DEFAULT_SUMMARY', () => {
  it('tem todos os campos numéricos zerados', () => {
    expect(DEFAULT_SUMMARY.plannedIncome).toBe(0);
    expect(DEFAULT_SUMMARY.plannedExpense).toBe(0);
    expect(DEFAULT_SUMMARY.projectedBalance).toBe(0);
    expect(DEFAULT_SUMMARY.currentBalance).toBe(0);
    expect(DEFAULT_SUMMARY.unplannedIncome).toBe(0);
    expect(DEFAULT_SUMMARY.unplannedExpense).toBe(0);
    expect(DEFAULT_SUMMARY.availableToSpend).toBe(0);
    expect(DEFAULT_SUMMARY.pendingCount).toBe(0);
  });
});

describe('findDuplicateRecurring', () => {
  const rec = (over: Partial<RecurrenceRuleResponseDTO>): RecurrenceRuleResponseDTO =>
    ({ id: '1', description: 'Aluguel', type: 'EXPENSE', status: 'ACTIVE',
       baseAmount: 1000, accountId: 'acc-1', accountName: 'Conta', rrule: 'FREQ=MONTHLY;BYMONTHDAY=1', startDate: '2026-01-01', ...over });

  it('encontra match ignorando caixa e espaços nas bordas', () => {
    const existing = [rec({ description: '  aLuGuel ' })];
    expect(findDuplicateRecurring(existing, 'Aluguel', 'EXPENSE')).toBeDefined();
  });

  it('diferencia por tipo (mesma descrição, tipo diferente não é duplicata)', () => {
    const existing = [rec({ description: 'Aluguel', type: 'EXPENSE' })];
    expect(findDuplicateRecurring(existing, 'Aluguel', 'INCOME')).toBeUndefined();
  });

  it('ignora recorrentes cancelados (status !== ACTIVE)', () => {
    const existing = [rec({ description: 'Aluguel', status: 'CANCELLED' })];
    expect(findDuplicateRecurring(existing, 'Aluguel', 'EXPENSE')).toBeUndefined();
  });

  it('retorna undefined quando não há match', () => {
    const existing = [rec({ description: 'Internet' })];
    expect(findDuplicateRecurring(existing, 'Aluguel', 'EXPENSE')).toBeUndefined();
  });
});
