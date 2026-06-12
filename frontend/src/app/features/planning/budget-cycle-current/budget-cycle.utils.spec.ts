import { describe, it, expect } from 'vitest';
import { buildSummary } from './budget-cycle.utils';
import { BudgetItemResponse } from '../../../core/api/fintechSaaSAPI.schemas';

function item(overrides: Partial<BudgetItemResponse>): BudgetItemResponse {
  return {
    id: '1',
    description: 'Item',
    amount: 100,
    type: 'EXPENSE',
    expectedDate: '2026-06-10',
    source: 'MANUAL',
    status: 'PENDING',
    ...overrides,
  } as BudgetItemResponse;
}

describe('buildSummary', () => {
  it('retorna zeros quando lista está vazia', () => {
    const s = buildSummary([], 0);
    expect(s.plannedIncome).toBe(0);
    expect(s.plannedExpense).toBe(0);
    expect(s.projectedBalance).toBe(0);
    expect(s.currentBalance).toBe(0);
    expect(s.pendingCount).toBe(0);
  });

  it('soma corretamente receitas planejadas', () => {
    const items = [
      item({ type: 'INCOME', amount: 3000, status: 'PENDING' }),
      item({ type: 'INCOME', amount: 5000, status: 'PENDING' }),
    ];
    const s = buildSummary(items, 0);
    expect(s.plannedIncome).toBe(8000);
    expect(s.plannedExpense).toBe(0);
  });

  it('calcula saldo projetado: openingBalance + plannedIncome - plannedExpense', () => {
    const items = [
      item({ type: 'INCOME', amount: 8000, status: 'PENDING' }),
      item({ type: 'EXPENSE', amount: 5400, status: 'PENDING' }),
    ];
    const s = buildSummary(items, 1000);
    expect(s.projectedBalance).toBe(3600); // 1000 + 8000 - 5400
  });

  it('separa realizado de pendente corretamente', () => {
    const items = [
      item({ type: 'INCOME', amount: 8000, status: 'REALIZED' }),
      item({ type: 'EXPENSE', amount: 2100, status: 'REALIZED' }),
      item({ type: 'EXPENSE', amount: 3300, status: 'PENDING' }),
    ];
    const s = buildSummary(items, 0);
    expect(s.realizedIncome).toBe(8000);
    expect(s.realizedExpense).toBe(2100);
    expect(s.currentBalance).toBe(5900); // 0 + 8000 - 2100
    expect(s.pendingCount).toBe(1);
  });

  it('ignora itens com status SKIPPED no realized', () => {
    const items = [
      item({ type: 'EXPENSE', amount: 500, status: 'SKIPPED' }),
    ];
    const s = buildSummary(items, 0);
    expect(s.realizedExpense).toBe(0);
    expect(s.pendingCount).toBe(0);
  });
});
