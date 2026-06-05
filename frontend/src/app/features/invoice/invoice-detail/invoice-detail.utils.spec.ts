import { describe, it, expect } from 'vitest';
import { computeBreakdown } from './invoice-detail.utils';
import { TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

const makeTransaction = (overrides: Partial<TransactionResponseDTO>): TransactionResponseDTO => ({
  id: 't1', description: 'Test', amount: 100, date: '2026-06-01',
  type: 'EXPENSE', status: 'PAID',
  installmentLabel: null, categoryName: 'Alimentação', categoryId: 'cat-1',
  categoryArchived: false, accountName: 'Nubank', accountId: 'cc-1',
  transferId: null, installmentGroupId: null, installmentGroupDescription: null,
  installmentNumber: null, totalInstallments: null,
  invoiceId: 'inv-1', invoiceDueDate: '2026-07-05', invoiceStatus: 'OPEN',
  ...overrides
});

describe('computeBreakdown', () => {
  it('agrupa transações por categoria e soma valores', () => {
    const txs = [
      makeTransaction({ id: 't1', categoryName: 'Alimentação', amount: 300 }),
      makeTransaction({ id: 't2', categoryName: 'Alimentação', amount: 200 }),
      makeTransaction({ id: 't3', categoryName: 'Transporte',  amount: 100 })
    ];
    const result = computeBreakdown(txs, 600);
    expect(result).toHaveLength(2);
    const alimentacao = result.find(r => r.categoryName === 'Alimentação')!;
    expect(alimentacao.count).toBe(2);
    expect(alimentacao.total).toBe(500);
  });

  it('trata null/undefined em categoryName como "Sem categoria"', () => {
    const txs = [
      makeTransaction({ id: 't1', categoryName: null, amount: 150 }),
      makeTransaction({ id: 't2', categoryName: undefined, amount: 50 })
    ];
    const result = computeBreakdown(txs, 200);
    expect(result).toHaveLength(1);
    expect(result[0].categoryName).toBe('Sem categoria');
    expect(result[0].total).toBe(200);
  });

  it('exclui transações CANCELLED do agrupamento', () => {
    const txs = [
      makeTransaction({ id: 't1', amount: 400, status: 'PAID' }),
      makeTransaction({ id: 't2', amount: 300, status: 'CANCELLED' })
    ];
    const result = computeBreakdown(txs, 400);
    expect(result[0].total).toBe(400);
  });

  it('ordena por valor absoluto decrescente', () => {
    const txs = [
      makeTransaction({ id: 't1', categoryName: 'Pequena', amount: 50 }),
      makeTransaction({ id: 't2', categoryName: 'Grande', amount: 500 }),
      makeTransaction({ id: 't3', categoryName: 'Média', amount: 200 })
    ];
    const result = computeBreakdown(txs, 750);
    expect(result[0].categoryName).toBe('Grande');
    expect(result[1].categoryName).toBe('Média');
    expect(result[2].categoryName).toBe('Pequena');
  });

  it('calcula porcentagem em relação ao totalExpense', () => {
    const txs = [
      makeTransaction({ id: 't1', categoryName: 'Alimentação', amount: 250 })
    ];
    const result = computeBreakdown(txs, 500);
    expect(result[0].percentage).toBeCloseTo(50, 1);
  });

  it('retorna percentage 0 quando totalExpense é 0', () => {
    const txs = [makeTransaction({ id: 't1', amount: 100, type: 'INCOME' })];
    const result = computeBreakdown(txs, 0);
    expect(result[0].percentage).toBe(0);
  });

  it('retorna array vazio quando não há transações ativas', () => {
    const txs = [makeTransaction({ id: 't1', status: 'CANCELLED' })];
    const result = computeBreakdown(txs, 0);
    expect(result).toHaveLength(0);
  });
});
