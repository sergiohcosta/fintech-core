import { describe, it, expect } from 'vitest';
import { groupByRelativePeriod } from './grouped-list.utils';
import { TransactionResponseDTO } from '../../../../core/api/fintechSaaSAPI.schemas';

function tx(id: string, date: string, type: 'INCOME' | 'EXPENSE' = 'EXPENSE', amount = 10): TransactionResponseDTO {
  return { id, description: 'd', amount, date, type, status: 'PAID' } as TransactionResponseDTO;
}

describe('groupByRelativePeriod', () => {
  const today = '2026-06-15'; // segunda-feira

  it('separa hoje e ontem', () => {
    const groups = groupByRelativePeriod([tx('a', '2026-06-15'), tx('b', '2026-06-14')], today);
    expect(groups[0].bucket).toBe('today');
    expect(groups[0].transactions.map(t => t.id)).toEqual(['a']);
    expect(groups[1].bucket).toBe('yesterday');
  });

  it('omite buckets sem transações', () => {
    const groups = groupByRelativePeriod([tx('a', '2026-06-15')], today);
    expect(groups).toHaveLength(1);
    expect(groups.every(g => g.transactions.length > 0)).toBe(true);
  });

  it('calcula income/expense/net por grupo', () => {
    const groups = groupByRelativePeriod(
      [tx('a', '2026-06-15', 'INCOME', 100), tx('b', '2026-06-15', 'EXPENSE', 40)],
      today,
    );
    expect(groups[0]).toMatchObject({ income: 100, expense: 40, net: 60 });
  });

  it('joga datas muito antigas no bucket older', () => {
    const groups = groupByRelativePeriod([tx('a', '2026-01-01')], today);
    expect(groups[0].bucket).toBe('older');
  });

  it('ordena transações dentro do grupo por data desc', () => {
    const groups = groupByRelativePeriod(
      [tx('a', '2026-05-02'), tx('b', '2026-05-10')], today,
    );
    // ambas em "older" → mais recente primeiro
    expect(groups[0].transactions.map(t => t.id)).toEqual(['b', 'a']);
  });

  it('classifica bordas de thisWeek corretamente (diff=2 e diff=6)', () => {
    // today = 2026-06-15; diff=2 → 2026-06-13; diff=6 → 2026-06-09
    const groups = groupByRelativePeriod(
      [tx('a', '2026-06-13'), tx('b', '2026-06-09')],
      today,
    );
    expect(groups[0].bucket).toBe('thisWeek');
    expect(groups[0].transactions.map(t => t.id)).toEqual(['a', 'b']);
  });

  it('classifica bordas de lastWeek corretamente (diff=7 e diff=13)', () => {
    // today = 2026-06-15; diff=7 → 2026-06-08; diff=13 → 2026-06-02
    const groups = groupByRelativePeriod(
      [tx('c', '2026-06-08'), tx('d', '2026-06-02')],
      today,
    );
    expect(groups[0].bucket).toBe('lastWeek');
    expect(groups[0].transactions.map(t => t.id)).toEqual(['c', 'd']);
  });
});
