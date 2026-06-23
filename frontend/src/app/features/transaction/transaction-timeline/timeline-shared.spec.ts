import { describe, it, expect } from 'vitest';
import { effectiveSortDate, dayTotals } from './timeline-shared';
import { TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

function tx(p: Partial<TransactionResponseDTO>): TransactionResponseDTO {
  return {
    id: p.id ?? 'x', description: 'd', amount: p.amount ?? 0,
    date: p.date ?? '2026-06-10', type: p.type ?? 'EXPENSE',
    status: p.status ?? 'PAID', ...p,
  } as TransactionResponseDTO;
}

describe('effectiveSortDate', () => {
  it('usa invoiceDueDate quando é parcela de cartão', () => {
    const t = tx({ date: '2026-06-10', installmentGroupId: 'g1', invoiceDueDate: '2026-07-05' });
    expect(effectiveSortDate(t)).toBe('2026-07-05');
  });
  it('usa date para avulsa de cartão (sem installmentGroup)', () => {
    const t = tx({ date: '2026-06-10', invoiceDueDate: '2026-07-05' });
    expect(effectiveSortDate(t)).toBe('2026-06-10');
  });
  it('usa date para transação comum', () => {
    expect(effectiveSortDate(tx({ date: '2026-06-10' }))).toBe('2026-06-10');
  });
});

describe('dayTotals', () => {
  it('soma receitas e despesas, calcula net, ignora canceladas', () => {
    const txs = [
      tx({ type: 'INCOME', amount: 100, status: 'PAID' }),
      tx({ type: 'EXPENSE', amount: 30, status: 'PAID' }),
      tx({ type: 'EXPENSE', amount: 999, status: 'CANCELLED' }),
    ];
    expect(dayTotals(txs)).toEqual({ income: 100, expense: 30, net: 70 });
  });
});
