import { describe, it, expect } from 'vitest';
import { buildBreakdown, BreakdownContext } from './budget-cycle-breakdown';
import {
  BudgetCycleSummary,
  BudgetItemResponse,
  TransactionResponseDTO,
} from '../../../core/api/fintechSaaSAPI.schemas';

const summary = (over: Partial<Required<BudgetCycleSummary>>): Required<BudgetCycleSummary> => ({
  plannedIncome: 0, plannedExpense: 0, projectedBalance: 0,
  realizedIncome: 0, realizedExpense: 0, currentBalance: 0, pendingCount: 0,
  unplannedIncome: 0, unplannedExpense: 0, availableToSpend: 0,
  dailyAllowance: null, remainingDays: null,
  ...over,
});

const tx = (
  type: 'INCOME' | 'EXPENSE',
  amount: number,
  status: 'PAID' | 'PENDING',
  description = 'avulsa',
): TransactionResponseDTO =>
  ({ id: 'x', description, amount, date: '2026-06-10', type, status }) as TransactionResponseDTO;

const item = (
  type: 'INCOME' | 'EXPENSE',
  amount: number,
  status: 'PENDING' | 'REALIZED' | 'SKIPPED',
  description = 'item',
  transactionStatus?: 'PAID' | 'PENDING',
): BudgetItemResponse => ({ description, amount, type, status, transactionStatus });

describe('buildBreakdown', () => {
  // Exemplo de referência da feature: opening 10.000, 4 avulsas (1000 PAID/1000 PENDING despesa,
  // 500 PAID/500 PENDING receita) → currentBalance 9.500, availableToSpend 9.000.
  const referenceCtx: BreakdownContext = {
    openingBalance: 10000,
    summary: summary({
      projectedBalance: 10000, currentBalance: 9500,
      unplannedIncome: 1000, unplannedExpense: 2000,
      availableToSpend: 9000, dailyAllowance: 310.34, remainingDays: 29,
    }),
    items: [],
    unplanned: [
      tx('EXPENSE', 1000, 'PAID'),
      tx('EXPENSE', 1000, 'PENDING'),
      tx('INCOME', 500, 'PAID'),
      tx('INCOME', 500, 'PENDING'),
    ],
  };

  it('available: conta tudo (PAID+PENDING) e reconcilia com availableToSpend', () => {
    const bd = buildBreakdown('available', referenceCtx);
    const [income, expense] = bd.sections;

    expect(income.total).toBe(1000);   // 0 planejado + 1000 avulso
    expect(expense.total).toBe(2000);  // 0 planejado + 2000 avulso
    expect(income.entries).toHaveLength(2);
    expect(expense.entries).toHaveLength(2);

    // opening + todas receitas − todas despesas = availableToSpend
    expect(referenceCtx.openingBalance + income.total - expense.total).toBe(9000);

    const para = bd.results.find(r => r.label === 'Para gastar');
    expect(para?.amount).toBe(9000);
    expect(bd.results.find(r => r.label === 'Por dia')?.amount).toBe(310.34);
  });

  it('balance: "Atual" conta só PAGAS e reconcilia com currentBalance', () => {
    const bd = buildBreakdown('balance', referenceCtx);
    const [income, expense] = bd.sections;

    expect(income.total).toBe(500);    // só a receita avulsa PAID
    expect(expense.total).toBe(1000);  // só a despesa avulsa PAID
    expect(income.entries).toHaveLength(1);
    expect(expense.entries).toHaveLength(1);

    // opening + entradas pagas − saídas pagas = currentBalance
    expect(referenceCtx.openingBalance + income.total - expense.total).toBe(9500);
    expect(bd.results.find(r => r.label === 'Atual (em caixa)')?.amount).toBe(9500);
  });

  it('balance: item realizado conta no caixa só se a transação está PAID', () => {
    const ctx: BreakdownContext = {
      openingBalance: 0,
      summary: summary({ currentBalance: 500 }),
      items: [
        item('EXPENSE', 500, 'REALIZED', 'Pago', 'PAID'),
        item('EXPENSE', 300, 'REALIZED', 'Agendado', 'PENDING'),
      ],
      unplanned: [],
    };
    const bd = buildBreakdown('balance', ctx);
    const expense = bd.sections[1];
    expect(expense.entries).toHaveLength(1);          // só o PAID entra
    expect(expense.entries[0].label).toBe('Pago');
    expect(expense.total).toBe(500);
  });

  it('income/expense: lista itens ativos (exclui SKIPPED) e usa totais do summary', () => {
    const ctx: BreakdownContext = {
      openingBalance: 1000,
      summary: summary({
        plannedIncome: 5000, plannedExpense: 500,
        realizedIncome: 0, realizedExpense: 500,
      }),
      items: [
        item('INCOME', 5000, 'PENDING', 'Salário'),
        item('EXPENSE', 500, 'REALIZED', 'Conta'),
        item('INCOME', 300, 'SKIPPED', 'Bônus ignorado'),
      ],
      unplanned: [],
    };

    const income = buildBreakdown('income', ctx);
    expect(income.sections[0].total).toBe(5000);
    expect(income.sections[0].entries).toHaveLength(1); // SKIPPED fora
    expect(income.sections[0].entries[0].label).toBe('Salário');
    expect(income.results.find(r => r.label === 'Previsto')?.amount).toBe(5000);

    const expense = buildBreakdown('expense', ctx);
    expect(expense.sections[0].entries[0].detail).toBe('realizado');
    expect(expense.results.find(r => r.label === 'Realizado (em caixa)')?.amount).toBe(500);
  });
});
