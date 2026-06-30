import { describe, it, expect } from 'vitest';
import { sortTransactions, applySort, getSortInfo, isGhost, exportToCsv } from './transaction-list.utils';
import type { TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

function tx(overrides: Partial<TransactionResponseDTO> = {}): TransactionResponseDTO {
  return {
    id: 'id-1',
    description: 'Test',
    amount: 100,
    date: '2024-01-15',
    type: 'EXPENSE',
    status: 'PENDING',
    ...overrides,
  } as TransactionResponseDTO;
}

describe('sortTransactions', () => {
  it('retorna a mesma ordem quando criteria está vazio', () => {
    const txs = [tx({ id: 'a' }), tx({ id: 'b' })];
    expect(sortTransactions(txs, []).map(t => t.id)).toEqual(['a', 'b']);
  });

  it('ordena por amount asc', () => {
    const txs = [tx({ id: 'a', amount: 300 }), tx({ id: 'b', amount: 100 }), tx({ id: 'c', amount: 200 })];
    expect(sortTransactions(txs, [{ col: 'amount', dir: 'asc' }]).map(t => t.id)).toEqual(['b', 'c', 'a']);
  });

  it('ordena por amount desc', () => {
    const txs = [tx({ id: 'a', amount: 100 }), tx({ id: 'b', amount: 300 })];
    expect(sortTransactions(txs, [{ col: 'amount', dir: 'desc' }]).map(t => t.id)).toEqual(['b', 'a']);
  });

  it('usa invoiceDueDate para parcelas de cartão no sort de data', () => {
    // effectiveSortDate(a) = '2024-02-15', effectiveSortDate(b) = '2024-01-20' → a > b em asc
    const a = tx({ id: 'a', date: '2024-01-10', installmentGroupId: 'g1', invoiceDueDate: '2024-02-15' });
    const b = tx({ id: 'b', date: '2024-01-20' });
    expect(sortTransactions([a, b], [{ col: 'date', dir: 'asc' }]).map(t => t.id)).toEqual(['b', 'a']);
  });

  it('ordena por status: PENDING < PAID < CANCELLED', () => {
    const txs = [
      tx({ id: 'a', status: 'CANCELLED' }),
      tx({ id: 'b', status: 'PAID' }),
      tx({ id: 'c', status: 'PENDING' }),
    ];
    expect(sortTransactions(txs, [{ col: 'status', dir: 'asc' }]).map(t => t.id)).toEqual(['c', 'b', 'a']);
  });

  it('ordena por type: INCOME < EXPENSE < transferência', () => {
    const txs = [
      tx({ id: 'a', type: 'EXPENSE' }),
      tx({ id: 'b', type: 'INCOME' }),
      tx({ id: 'c', type: 'EXPENSE', transferId: 'tr1' }),
    ];
    expect(sortTransactions(txs, [{ col: 'type', dir: 'asc' }]).map(t => t.id)).toEqual(['b', 'a', 'c']);
  });

  it('multi-critério: status asc depois amount desc', () => {
    const txs = [
      tx({ id: 'a', status: 'PENDING', amount: 100 }),
      tx({ id: 'b', status: 'PENDING', amount: 200 }),
      tx({ id: 'c', status: 'PAID',    amount: 50  }),
    ];
    expect(
      sortTransactions(txs, [{ col: 'status', dir: 'asc' }, { col: 'amount', dir: 'desc' }]).map(t => t.id)
    ).toEqual(['b', 'a', 'c']);
  });

  it('coloca category null ao final (asc)', () => {
    const txs = [
      tx({ id: 'a', categoryName: undefined }),
      tx({ id: 'b', categoryName: 'Alimentação' }),
    ];
    expect(sortTransactions(txs, [{ col: 'category', dir: 'asc' }]).map(t => t.id)).toEqual(['b', 'a']);
  });

  it('coloca account null ao final (asc)', () => {
    const txs = [
      tx({ id: 'a', accountName: undefined }),
      tx({ id: 'b', accountName: 'Nubank' }),
    ];
    expect(sortTransactions(txs, [{ col: 'account', dir: 'asc' }]).map(t => t.id)).toEqual(['b', 'a']);
  });

  it('não muta o array original', () => {
    const txs = [tx({ id: 'a', amount: 300 }), tx({ id: 'b', amount: 100 })];
    sortTransactions(txs, [{ col: 'amount', dir: 'asc' }]);
    expect(txs.map(t => t.id)).toEqual(['a', 'b']);
  });
});

describe('applySort', () => {
  it('click no critério primário inverte a direção', () => {
    expect(applySort([{ col: 'date', dir: 'desc' }], 'date', false))
      .toEqual([{ col: 'date', dir: 'asc' }]);
  });

  it('click em coluna não-primária substitui todos os critérios', () => {
    expect(applySort([{ col: 'date', dir: 'desc' }], 'amount', false))
      .toEqual([{ col: 'amount', dir: 'asc' }]);
  });

  it('click com múltiplos critérios existentes substitui por apenas a nova coluna', () => {
    const criteria = [{ col: 'date', dir: 'desc' }, { col: 'status', dir: 'asc' }];
    expect(applySort(criteria as any, 'amount', false))
      .toEqual([{ col: 'amount', dir: 'asc' }]);
  });

  it('shift+click em coluna existente inverte sua direção mantendo posição', () => {
    const criteria = [{ col: 'date', dir: 'desc' }, { col: 'amount', dir: 'asc' }];
    expect(applySort(criteria as any, 'amount', true))
      .toEqual([{ col: 'date', dir: 'desc' }, { col: 'amount', dir: 'desc' }]);
  });

  it('shift+click em coluna ausente acrescenta ao final com asc', () => {
    expect(applySort([{ col: 'date', dir: 'desc' }], 'status', true))
      .toEqual([{ col: 'date', dir: 'desc' }, { col: 'status', dir: 'asc' }]);
  });
});

describe('getSortInfo', () => {
  it('retorna null quando coluna não está nos critérios', () => {
    expect(getSortInfo([{ col: 'date', dir: 'desc' }], 'amount')).toBeNull();
  });

  it('retorna priority 1 e dir para a coluna primária', () => {
    expect(getSortInfo([{ col: 'date', dir: 'desc' }], 'date'))
      .toEqual({ priority: 1, dir: 'desc' });
  });

  it('retorna priority 2 para critério secundário', () => {
    const criteria = [{ col: 'date', dir: 'desc' }, { col: 'amount', dir: 'asc' }];
    expect(getSortInfo(criteria as any, 'amount'))
      .toEqual({ priority: 2, dir: 'asc' });
  });
});

describe('isGhost', () => {
  it('true só quando projected === true', () => {
    expect(isGhost(tx({ projected: true }))).toBe(true);
    expect(isGhost(tx({ projected: false }))).toBe(false);
    expect(isGhost(tx())).toBe(false); // sem o campo = transação real
  });
});

describe('exportToCsv', () => {
  it('gera cabeçalho e linha com campos corretos', () => {
    const csv = exportToCsv([{
      id: '1', description: 'Netflix', amount: 45.9, date: '2026-06-15',
      type: 'EXPENSE', status: 'PAID', categoryPath: 'Lazer → Streaming',
      accountName: 'Nubank', installmentNumber: null, totalInstallments: null,
      invoiceDueDate: null, projected: false,
    } as TransactionResponseDTO]);

    const [header, row] = csv.split('\n');
    expect(header).toBe('Data;Descrição;Valor;Tipo;Status;Categoria;Conta;Parcela;Fatura');
    expect(row).toBe('15/06/2026;Netflix;45,90;Despesa;Pago;Lazer → Streaming;Nubank;;');
  });

  it('usa categoryName quando categoryPath é null', () => {
    const csv = exportToCsv([{
      id: '2', description: 'Mercado', amount: 200, date: '2026-06-01',
      type: 'EXPENSE', status: 'PENDING', categoryPath: null, categoryName: 'Alimentação',
      accountName: 'Nubank', installmentNumber: null, totalInstallments: null,
      invoiceDueDate: null, projected: false,
    } as TransactionResponseDTO]);

    const row = csv.split('\n')[1];
    expect(row).toContain('Alimentação');
  });

  it('formata parcela como N/Total quando disponível', () => {
    const csv = exportToCsv([{
      id: '3', description: 'TV', amount: 300, date: '2026-06-01',
      type: 'EXPENSE', status: 'PENDING', categoryPath: null, categoryName: null,
      accountName: null, installmentNumber: 2, totalInstallments: 6,
      invoiceDueDate: null, projected: false,
    } as TransactionResponseDTO]);

    const row = csv.split('\n')[1];
    expect(row).toContain('2/6');
  });

  it('formata fatura como mmm/yyyy quando invoiceDueDate está presente', () => {
    const csv = exportToCsv([{
      id: '4', description: 'Compra', amount: 50, date: '2026-05-10',
      type: 'EXPENSE', status: 'PAID', categoryPath: null, categoryName: null,
      accountName: null, installmentNumber: null, totalInstallments: null,
      invoiceDueDate: '2026-08-10', projected: false,
    } as TransactionResponseDTO]);

    const row = csv.split('\n')[1];
    expect(row).toContain('ago/2026');
  });

  it('escapa campo com ponto-e-vírgula em aspas duplas (RFC 4180)', () => {
    const csv = exportToCsv([{
      id: '5', description: 'A; B', amount: 10, date: '2026-06-01',
      type: 'INCOME', status: 'PAID', categoryPath: null, categoryName: null,
      accountName: null, installmentNumber: null, totalInstallments: null,
      invoiceDueDate: null, projected: false,
    } as TransactionResponseDTO]);

    const row = csv.split('\n')[1];
    expect(row).toContain('"A; B"');
  });

  it('mapeia transferId para Tipo=Transferência', () => {
    const csv = exportToCsv([{
      id: '6', description: 'TED', amount: 500, date: '2026-06-01',
      type: 'EXPENSE', status: 'PAID', categoryPath: null, categoryName: null,
      accountName: null, installmentNumber: null, totalInstallments: null,
      invoiceDueDate: null, projected: false, transferId: 'tr-uuid',
    } as TransactionResponseDTO]);

    const row = csv.split('\n')[1];
    expect(row).toContain('Transferência');
  });
});
