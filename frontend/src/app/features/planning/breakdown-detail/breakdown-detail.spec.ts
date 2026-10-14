import { describe, expect, it } from 'vitest';
import { parseBreakdownCard } from './breakdown-detail';

describe('parseBreakdownCard', () => {
  it('aceita os quatro cartões que podem abrir composição', () => {
    expect(['income', 'expense', 'balance', 'available'].map(parseBreakdownCard))
      .toEqual(['income', 'expense', 'balance', 'available']);
  });

  it('rejeita parâmetros de rota desconhecidos', () => {
    expect(parseBreakdownCard(null)).toBeNull();
    expect(parseBreakdownCard('category-123')).toBeNull();
  });
});
