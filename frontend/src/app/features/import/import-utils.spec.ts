import { describe, it, expect } from 'vitest';
import type {
  CategoryResponseDTO,
  StagedTransactionResponseDTO,
} from '../../core/api/fintechSaaSAPI.schemas';
import {
  anyPendingHasAccount,
  applyBulkField,
  buildCommitRequest,
  conflictMessage,
  confidenceLevel,
  directionLabel,
  fieldConfidence,
  fieldValue,
  fieldValueAsString,
  flattenCategories,
  formatAmountCurrency,
  formatAmountDisplay,
  formatConfidence,
  formatConflictDate,
  formatDatePtBr,
  isLowConfidence,
  parseDuplicateConflict,
  type CommitRow,
} from './import-utils';

const stagedFixture: StagedTransactionResponseDTO = {
  id: 's1',
  batchId: 'b1',
  fields: {
    amount: { value: 127.5, confidence: 0.98 },
    transaction_date: { value: '2026-06-28', confidence: 0.6 },
    description: { value: 'PADARIA', confidence: 0.9 },
  },
  requiresReview: false,
  status: 'PENDING',
};

describe('formatConfidence', () => {
  it('formata como porcentagem inteira', () => {
    expect(formatConfidence(0.98)).toBe('98%');
    expect(formatConfidence(0)).toBe('0%');
  });

  it('clampa acima de 1 e devolve travessão para null', () => {
    expect(formatConfidence(1.5)).toBe('100%');
    expect(formatConfidence(null)).toBe('—');
  });
});

describe('isLowConfidence / confidenceLevel', () => {
  it('null ou abaixo da régua (0.95) é baixa', () => {
    expect(isLowConfidence(null)).toBe(true);
    expect(isLowConfidence(0.9)).toBe(true);
    expect(confidenceLevel(0.9)).toBe('low');
  });

  it('igual ou acima da régua é alta', () => {
    expect(isLowConfidence(0.95)).toBe(false);
    expect(isLowConfidence(0.99)).toBe(false);
    expect(confidenceLevel(0.99)).toBe('high');
  });
});

describe('directionLabel', () => {
  it('credit é entrada, o resto é saída', () => {
    expect(directionLabel('credit')).toBe('Entrada (receita)');
    expect(directionLabel('debit')).toBe('Saída (despesa)');
    expect(directionLabel(undefined)).toBe('Saída (despesa)');
  });
});

describe('extração de campos', () => {
  it('lê valor e confiança do JSONB', () => {
    expect(fieldValue(stagedFixture, 'amount')).toBe(127.5);
    expect(fieldConfidence(stagedFixture, 'amount')).toBe(0.98);
    expect(fieldValueAsString(stagedFixture, 'amount')).toBe('127.5');
  });

  it('campo ausente vira null / string vazia', () => {
    expect(fieldValue(stagedFixture, 'payment_method')).toBeNull();
    expect(fieldConfidence(stagedFixture, 'payment_method')).toBeNull();
    expect(fieldValueAsString(stagedFixture, 'payment_method')).toBe('');
  });
});

describe('buildCommitRequest', () => {
  it('inclui só linhas PENDING com conta; categoria vazia vira null', () => {
    const rows: CommitRow[] = [
      { stagedId: 's1', accountId: 'acc-1', categoryId: 'cat-1', status: 'PENDING' },
      { stagedId: 's2', accountId: 'acc-2', categoryId: '', status: 'PENDING' },
      { stagedId: 's3', accountId: null, categoryId: null, status: 'PENDING' }, // sem conta → fora
      { stagedId: 's4', accountId: 'acc-4', categoryId: null, status: 'CONFIRMED' }, // já lançada → fora
    ];
    const req = buildCommitRequest(rows);
    expect(req.items).toHaveLength(2);
    expect(req.items[0]).toEqual({ stagedId: 's1', accountId: 'acc-1', categoryId: 'cat-1' });
    expect(req.items[1]).toEqual({ stagedId: 's2', accountId: 'acc-2', categoryId: null });
  });
});

describe('anyPendingHasAccount', () => {
  it('true quando toda PENDING tem conta (comportamento antigo continua válido)', () => {
    expect(
      anyPendingHasAccount([
        { stagedId: 's1', accountId: 'a', categoryId: null, status: 'PENDING' },
      ]),
    ).toBe(true);
  });

  it('false quando não há nenhuma PENDING com conta', () => {
    expect(
      anyPendingHasAccount([
        { stagedId: 's1', accountId: null, categoryId: null, status: 'PENDING' },
      ]),
    ).toBe(false);
    expect(anyPendingHasAccount([])).toBe(false);
    // CONFIRMED com conta não conta — só PENDING habilita o "Confirmar"
    expect(
      anyPendingHasAccount([
        { stagedId: 's1', accountId: 'a', categoryId: null, status: 'CONFIRMED' },
      ]),
    ).toBe(false);
  });

  it('true com mistura — só precisa de UMA PENDING com conta (gate relaxado, Fase 2 metade B)', () => {
    const rows: CommitRow[] = [
      { stagedId: 's1', accountId: 'a', categoryId: null, status: 'PENDING' },
      { stagedId: 's2', accountId: null, categoryId: null, status: 'PENDING' },
      { stagedId: 's3', accountId: null, categoryId: null, status: 'PENDING' },
    ];
    expect(anyPendingHasAccount(rows)).toBe(true);
  });
});

describe('applyBulkField', () => {
  const makeRows = (n: number): CommitRow[] =>
    Array.from({ length: n }, (_, i) => ({
      stagedId: `s${i}`,
      accountId: null,
      categoryId: null,
      status: 'PENDING',
    }));

  it('aplica o valor só ao subconjunto selecionado, preservando ordem e demais linhas', () => {
    const rows = makeRows(10);
    const selected = ['s2', 's4', 's7'];
    const result = applyBulkField(rows, selected, 'accountId', 'acc-1');

    expect(result).toHaveLength(10);
    expect(result.map((r) => r.stagedId)).toEqual(rows.map((r) => r.stagedId));
    for (const row of result) {
      if (selected.includes(row.stagedId)) {
        expect(row.accountId).toBe('acc-1');
      } else {
        expect(row.accountId).toBeNull();
      }
    }
  });

  it('conjunto vazio devolve array com o mesmo conteúdo (nenhuma linha muda)', () => {
    const rows = makeRows(3);
    const result = applyBulkField(rows, [], 'accountId', 'acc-1');
    expect(result).toEqual(rows);
    expect(result).not.toBe(rows);
  });

  it('funciona tanto para accountId quanto para categoryId', () => {
    const rows = makeRows(3);
    const result = applyBulkField(rows, ['s0', 's1'], 'categoryId', 'cat-9');
    expect(result[0].categoryId).toBe('cat-9');
    expect(result[1].categoryId).toBe('cat-9');
    expect(result[2].categoryId).toBeNull();
  });

  it('não muta o array original', () => {
    const rows = makeRows(2);
    applyBulkField(rows, ['s0'], 'accountId', 'acc-1');
    expect(rows[0].accountId).toBeNull();
  });

  it('aceita Set além de array de ids selecionados', () => {
    const rows = makeRows(3);
    const result = applyBulkField(rows, new Set(['s1']), 'accountId', 'acc-2');
    expect(result[1].accountId).toBe('acc-2');
    expect(result[0].accountId).toBeNull();
  });
});

describe('parseDuplicateConflict', () => {
  it('reconhece um 409 com batchId no corpo', () => {
    const error = {
      status: 409,
      error: { batchId: 'b1', createdAt: '2026-07-01T10:00:00', filename: 'extrato.csv' },
    };
    expect(parseDuplicateConflict(error)).toEqual({
      batchId: 'b1',
      createdAt: '2026-07-01T10:00:00',
      filename: 'extrato.csv',
    });
  });

  it('devolve null para qualquer outro erro (400, 500, sem batchId)', () => {
    expect(
      parseDuplicateConflict({ status: 400, error: { message: 'formato não reconhecido' } }),
    ).toBeNull();
    expect(parseDuplicateConflict({ status: 500 })).toBeNull();
    expect(parseDuplicateConflict({ status: 409, error: {} })).toBeNull();
    expect(parseDuplicateConflict(null)).toBeNull();
  });

  it('createdAt/filename ausentes viram null, não quebram', () => {
    expect(parseDuplicateConflict({ status: 409, error: { batchId: 'b1' } })).toEqual({
      batchId: 'b1',
      createdAt: null,
      filename: null,
    });
  });
});

describe('formatConflictDate', () => {
  it('formata data ISO em pt-BR', () => {
    expect(formatConflictDate('2026-07-01T10:30:00')).toContain('2026');
  });

  it('null/inválida não quebra a UI', () => {
    expect(formatConflictDate(null)).toBe('data desconhecida');
    expect(formatConflictDate('lixo-nao-e-data')).toBe('lixo-nao-e-data');
  });
});

describe('conflictMessage', () => {
  it('inclui data e nome do arquivo quando presentes', () => {
    const msg = conflictMessage({
      batchId: 'b1',
      createdAt: '2026-07-01T10:00:00',
      filename: 'extrato.csv',
    });
    expect(msg).toContain('extrato.csv');
    expect(msg).toContain('2026');
  });

  it('sem filename, não inclui parênteses vazios', () => {
    const msg = conflictMessage({ batchId: 'b1', createdAt: null, filename: null });
    expect(msg).not.toContain('()');
    expect(msg).toContain('data desconhecida');
  });
});

describe('flattenCategories', () => {
  it('achata a árvore com caminho no nome', () => {
    const tree: CategoryResponseDTO[] = [
      {
        id: 'p',
        name: 'Pets',
        icon: 'pets',
        color: '#000',
        archived: false,
        children: [
          { id: 'c', name: 'Ração', icon: 'x', color: '#000', archived: false, children: [] },
        ],
      },
      { id: 'a', name: 'Alimentação', icon: 'y', color: '#000', archived: false, children: [] },
    ];
    const flat = flattenCategories(tree);
    expect(flat).toEqual([
      { id: 'p', name: 'Pets' },
      { id: 'c', name: 'Pets › Ração' },
      { id: 'a', name: 'Alimentação' },
    ]);
  });

  it('lida com null/undefined', () => {
    expect(flattenCategories(null)).toEqual([]);
    expect(flattenCategories(undefined)).toEqual([]);
  });
});

describe('formatAmountCurrency', () => {
  it('formata valor canônico (ponto decimal) como moeda pt-BR', () => {
    expect(formatAmountCurrency('1234.5')).toBe('R$\u00a01.234,50');
    expect(formatAmountCurrency('7')).toBe('R$\u00a07,00');
  });

  it('vazio ou inválido vira travessão', () => {
    expect(formatAmountCurrency('')).toBe('—');
    expect(formatAmountCurrency('abc')).toBe('—');
  });
});

describe('formatAmountDisplay', () => {
  it('formata só os dígitos pt-BR, sem símbolo de moeda', () => {
    expect(formatAmountDisplay('1234.5')).toBe('1.234,50');
  });

  it('vazio ou inválido vira string vazia (input some, não "—")', () => {
    expect(formatAmountDisplay('')).toBe('');
    expect(formatAmountDisplay('abc')).toBe('');
  });
});

describe('formatDatePtBr', () => {
  it('converte ISO (yyyy-MM-dd) para dd/mm/aaaa', () => {
    expect(formatDatePtBr('2026-06-28')).toBe('28/06/2026');
  });

  it('vazio vira travessão; string não parseável é devolvida como está', () => {
    expect(formatDatePtBr('')).toBe('—');
    expect(formatDatePtBr('não-é-data')).toBe('não-é-data');
  });
});
