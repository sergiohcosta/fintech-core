import { describe, it, expect } from 'vitest';
import { buildDisplayRows, GroupRow, DisplayRow } from './transaction-list.utils';

describe('buildDisplayRows', () => {
  it('agrupa transações do mesmo installmentGroupId em uma única linha group', () => {
    const txs: any[] = [
      { id: '1', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PAID', installmentNumber: 1, totalInstallments: 3, amount: 1000, date: '2026-01-01', type: 'EXPENSE' },
      { id: '2', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PENDING', installmentNumber: 2, totalInstallments: 3, amount: 1000, date: '2026-02-01', type: 'EXPENSE' },
      { id: '3', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PENDING', installmentNumber: 3, totalInstallments: 3, amount: 1000, date: '2026-03-01', type: 'EXPENSE' },
    ];

    const rows = buildDisplayRows(txs, new Set());

    expect(rows).toHaveLength(1);
    expect(rows[0].kind).toBe('group');
    const groupRow = rows[0] as GroupRow;
    expect(groupRow.paidInstallments).toBe(1);
    expect(groupRow.totalInstallments).toBe(3);
  });

  it('expande parcelas individuais quando grupo está no expandedGroups', () => {
    const txs: any[] = [
      { id: '1', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PENDING', installmentNumber: 1, totalInstallments: 2, amount: 500, date: '2026-01-01', type: 'EXPENSE' },
      { id: '2', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PENDING', installmentNumber: 2, totalInstallments: 2, amount: 500, date: '2026-02-01', type: 'EXPENSE' },
    ];

    const rows = buildDisplayRows(txs, new Set(['g1']));

    expect(rows).toHaveLength(3);
    expect(rows[0].kind).toBe('group');
    expect(rows[1].kind).toBe('transaction');
    expect(rows[2].kind).toBe('transaction');
  });

  it('mantém transações avulsas como linhas single separadas', () => {
    const txs: any[] = [
      { id: '1', description: 'Salário', status: 'PAID', amount: 5000, date: '2026-01-05', type: 'INCOME' },
      { id: '2', description: 'Mercado', status: 'PAID', amount: 300, date: '2026-01-10', type: 'EXPENSE' },
    ];

    const rows = buildDisplayRows(txs, new Set());

    expect(rows).toHaveLength(2);
    rows.forEach((r: DisplayRow) => expect(r.kind).toBe('single'));
  });
});
