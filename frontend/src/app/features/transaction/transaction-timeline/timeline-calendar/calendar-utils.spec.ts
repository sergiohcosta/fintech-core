import { describe, it, expect } from 'vitest';
import { buildMonthGrid, formatMonthLabel } from './calendar-utils';
import { TransactionResponseDTO } from '../../../../core/api/fintechSaaSAPI.schemas';

function tx(p: Partial<TransactionResponseDTO>): TransactionResponseDTO {
  return { id: p.id ?? 'x', description: 'd', amount: p.amount ?? 0,
    date: p.date ?? '2026-06-10', type: p.type ?? 'EXPENSE',
    status: p.status ?? 'PAID', ...p } as TransactionResponseDTO;
}

describe('buildMonthGrid', () => {
  it('sempre retorna 42 células', () => {
    expect(buildMonthGrid('2026-06-15', [])).toHaveLength(42);
  });

  it('junho/2026 começa numa segunda — 1 célula de padding antes do dia 1', () => {
    // 2026-06-01 é segunda-feira; semana começa no domingo → 1 célula vazia
    const grid = buildMonthGrid('2026-06-15', []);
    expect(grid[0].date).toBeNull();
    expect(grid[1].dayOfMonth).toBe(1);
    expect(grid[1].inMonth).toBe(true);
  });

  it('aloca transação no dia certo pela effectiveSortDate', () => {
    const grid = buildMonthGrid('2026-06-15', [tx({ id: 't1', date: '2026-06-10' })]);
    const cell = grid.find(c => c.date === '2026-06-10')!;
    expect(cell.transactions.map(t => t.id)).toEqual(['t1']);
    expect(cell.totals.expense).toBe(0); // amount default 0
  });

  it('parcela de cartão cai no dia da invoiceDueDate, não da date', () => {
    const grid = buildMonthGrid('2026-06-15', [
      tx({ id: 'p', date: '2026-05-20', installmentGroupId: 'g', invoiceDueDate: '2026-06-08' }),
    ]);
    expect(grid.find(c => c.date === '2026-06-08')!.transactions[0].id).toBe('p');
  });
});

describe('formatMonthLabel', () => {
  it('formata mês e ano em pt-BR', () => {
    expect(formatMonthLabel('2026-06-15')).toBe('Junho de 2026');
  });
});
