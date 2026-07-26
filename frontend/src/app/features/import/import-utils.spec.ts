import { describe, it, expect } from 'vitest';
import type {
  CategoryResponseDTO,
  StagedTransactionResponseDTO,
} from '../../core/api/fintechSaaSAPI.schemas';
import {
  allPendingHaveAccount,
  buildCommitRequest,
  confidenceLevel,
  directionLabel,
  fieldConfidence,
  fieldValue,
  fieldValueAsString,
  flattenCategories,
  formatConfidence,
  isLowConfidence,
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

describe('allPendingHaveAccount', () => {
  it('true só quando toda PENDING tem conta e há ao menos uma', () => {
    expect(allPendingHaveAccount([{ stagedId: 's1', accountId: 'a', categoryId: null, status: 'PENDING' }])).toBe(true);
    expect(allPendingHaveAccount([{ stagedId: 's1', accountId: null, categoryId: null, status: 'PENDING' }])).toBe(false);
    expect(allPendingHaveAccount([])).toBe(false);
    // CONFIRMED não bloqueia; mas precisa existir ao menos uma PENDING com conta
    expect(allPendingHaveAccount([{ stagedId: 's1', accountId: 'a', categoryId: null, status: 'CONFIRMED' }])).toBe(false);
  });
});

describe('flattenCategories', () => {
  it('achata a árvore com caminho no nome', () => {
    const tree: CategoryResponseDTO[] = [
      {
        id: 'p', name: 'Pets', icon: 'pets', color: '#000', archived: false, children: [
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
