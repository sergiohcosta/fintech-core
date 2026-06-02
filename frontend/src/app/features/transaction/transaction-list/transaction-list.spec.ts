import { describe, it, expect } from 'vitest';
import { buildDisplayRows, DisplayRow, InstallmentGroupInfo } from './transaction-list.utils';

describe('buildDisplayRows', () => {
  it('cada parcela do mesmo grupo aparece como linha individual', () => {
    const txs: any[] = [
      { id: '1', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PAID',    installmentNumber: 1, totalInstallments: 3, amount: 1000, date: '2026-01-01', type: 'EXPENSE' },
      { id: '2', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PENDING', installmentNumber: 2, totalInstallments: 3, amount: 1000, date: '2026-02-01', type: 'EXPENSE' },
      { id: '3', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PENDING', installmentNumber: 3, totalInstallments: 3, amount: 1000, date: '2026-03-01', type: 'EXPENSE' },
    ];

    const rows = buildDisplayRows(txs, new Set());

    expect(rows).toHaveLength(3);
    rows.forEach(r => expect(r.kind).toBe('installment'));
  });

  it('info do grupo está acessível em cada parcela individual', () => {
    const txs: any[] = [
      { id: '1', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PAID',    installmentNumber: 1, totalInstallments: 2, amount: 500, date: '2026-01-01', type: 'EXPENSE' },
      { id: '2', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PENDING', installmentNumber: 2, totalInstallments: 2, amount: 500, date: '2026-02-01', type: 'EXPENSE' },
    ];

    const rows = buildDisplayRows(txs, new Set());

    expect(rows).toHaveLength(2);
    rows.forEach(r => {
      expect(r.kind).toBe('installment');
      const group = (r as any).group as InstallmentGroupInfo;
      expect(group.totalInstallments).toBe(2);
      expect(group.paidInstallments).toBe(1);
      expect(group.description).toBe('Notebook');
    });
  });

  it('expande uma parcela inserindo uma linha de detalhe logo após ela', () => {
    const txs: any[] = [
      { id: '1', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PAID',    installmentNumber: 1, totalInstallments: 2, amount: 500, date: '2026-01-01', type: 'EXPENSE' },
      { id: '2', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PENDING', installmentNumber: 2, totalInstallments: 2, amount: 500, date: '2026-02-01', type: 'EXPENSE' },
    ];

    const rows = buildDisplayRows(txs, new Set(['1']));

    expect(rows).toHaveLength(3);
    expect(rows[0].kind).toBe('installment');
    expect((rows[0] as any).isExpanded).toBe(true);
    expect(rows[1].kind).toBe('installment-detail');
    expect(rows[2].kind).toBe('installment');
    expect((rows[2] as any).isExpanded).toBe(false);
  });

  it('mantém transações avulsas como linhas single separadas', () => {
    const txs: any[] = [
      { id: '1', description: 'Salário', status: 'PAID',    amount: 5000, date: '2026-01-05', type: 'INCOME' },
      { id: '2', description: 'Mercado', status: 'PENDING', amount: 300,  date: '2026-01-10', type: 'EXPENSE' },
    ];

    const rows = buildDisplayRows(txs, new Set());

    expect(rows).toHaveLength(2);
    rows.forEach((r: DisplayRow) => expect(r.kind).toBe('single'));
  });

  it('mistura de avulsas e parceladas mantém a ordem original', () => {
    const txs: any[] = [
      { id: '1', description: 'Salário',   status: 'PAID',    amount: 5000, date: '2026-01-01', type: 'INCOME' },
      { id: '2', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PENDING', installmentNumber: 1, totalInstallments: 2, amount: 500, date: '2026-01-05', type: 'EXPENSE' },
      { id: '3', description: 'Mercado',   status: 'PAID',    amount: 300,  date: '2026-01-10', type: 'EXPENSE' },
      { id: '4', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PENDING', installmentNumber: 2, totalInstallments: 2, amount: 500, date: '2026-02-05', type: 'EXPENSE' },
    ];

    const rows = buildDisplayRows(txs, new Set());

    expect(rows).toHaveLength(4);
    expect(rows[0].kind).toBe('single');
    expect(rows[1].kind).toBe('installment');
    expect(rows[2].kind).toBe('single');
    expect(rows[3].kind).toBe('installment');
  });
});
