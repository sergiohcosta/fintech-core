import { describe, it, expect } from 'vitest';
import { calculateMarkerPosition, resolveCollisions } from './horizontal-utils';
import { TransactionResponseDTO } from '../../../../core/api/fintechSaaSAPI.schemas';

function tx(id: string, date: string): TransactionResponseDTO {
  return { id, description: 'd', amount: 1, date, type: 'EXPENSE', status: 'PAID' } as TransactionResponseDTO;
}

describe('calculateMarkerPosition', () => {
  it('início do range fica em x=0', () => {
    expect(calculateMarkerPosition('2026-06-01', '2026-06-01', '2026-06-30', 290)).toBe(0);
  });
  it('fim do range fica na largura total', () => {
    expect(calculateMarkerPosition('2026-06-30', '2026-06-01', '2026-06-30', 290)).toBe(290);
  });
  it('meio do range fica no meio', () => {
    // 2026-06-16 é o ponto médio de 01..30 (15 de 29 dias) → ~50%
    const x = calculateMarkerPosition('2026-06-16', '2026-06-01', '2026-06-30', 290);
    expect(Math.round(x)).toBe(150);
  });
  it('range degenerado retorna o centro', () => {
    expect(calculateMarkerPosition('2026-06-10', '2026-06-10', '2026-06-10', 200)).toBe(100);
  });
});

describe('resolveCollisions', () => {
  it('agrupa transações do mesmo dia num marcador só', () => {
    const markers = resolveCollisions(
      [tx('a', '2026-06-10'), tx('b', '2026-06-10'), tx('c', '2026-06-20')],
      '2026-06-01', '2026-06-30', 290,
    );
    expect(markers).toHaveLength(2);
    const d10 = markers.find(m => m.date === '2026-06-10')!;
    expect(d10.transactions.map(t => t.id).sort()).toEqual(['a', 'b']);
  });

  it('atribui stackIndex sequencial dentro do marcador', () => {
    const markers = resolveCollisions(
      [tx('a', '2026-06-10'), tx('b', '2026-06-10')], '2026-06-01', '2026-06-30', 290,
    );
    expect(markers[0].stackIndex).toBe(0);
  });
});
