import { describe, it, expect } from 'vitest';
import { parseAmountInput, formatLocalDate } from './transaction-form.utils';

describe('parseAmountInput (#148)', () => {
  it('interpreta ponto como decimal quando seguido de 1-2 dígitos no fim', () => {
    expect(parseAmountInput('1234.56')).toBe(1234.56); // antes virava 123456
    expect(parseAmountInput('1234.5')).toBe(1234.5);
    expect(parseAmountInput('0.99')).toBe(0.99);
  });

  it('interpreta formato pt-BR (vírgula decimal, ponto milhar)', () => {
    expect(parseAmountInput('1.234,56')).toBe(1234.56);
    expect(parseAmountInput('1234,56')).toBe(1234.56);
    expect(parseAmountInput('1.234.567,89')).toBe(1234567.89);
  });

  it('trata ponto como milhar quando não é decimal (1-2 dígitos)', () => {
    expect(parseAmountInput('1.234')).toBe(1234);
    expect(parseAmountInput('1234')).toBe(1234);
  });

  it('ignora símbolos e espaços (R$, etc.)', () => {
    expect(parseAmountInput('R$ 1.234,56')).toBe(1234.56);
    expect(parseAmountInput('R$ 1234.56')).toBe(1234.56);
  });

  it('retorna null para entrada sem número', () => {
    expect(parseAmountInput('')).toBeNull();
    expect(parseAmountInput('abc')).toBeNull();
  });
});

describe('formatLocalDate (#148)', () => {
  it('formata usando componentes locais, sem passar por UTC (não gera D-1)', () => {
    // Meia-noite local — toISOString poderia retornar o dia anterior em fuso positivo.
    expect(formatLocalDate(new Date(2026, 6, 5))).toBe('2026-07-05'); // mês 6 = julho (0-indexed)
    expect(formatLocalDate(new Date(2026, 0, 1))).toBe('2026-01-01');
    expect(formatLocalDate(new Date(2026, 11, 31))).toBe('2026-12-31');
  });
});
