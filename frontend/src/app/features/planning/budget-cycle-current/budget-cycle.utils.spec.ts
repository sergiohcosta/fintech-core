import { describe, it, expect } from 'vitest';
import { DEFAULT_SUMMARY } from './budget-cycle.utils';

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
