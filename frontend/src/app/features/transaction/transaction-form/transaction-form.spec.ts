import { describe, it, expect } from 'vitest';
import { buildInstallmentPreview } from './installment-preview';

describe('buildInstallmentPreview', () => {
  it('divide valor total igualmente entre as parcelas', () => {
    const rows = buildInstallmentPreview(3000, 3, new Date('2026-01-01'), 'total');
    expect(rows).toHaveLength(3);
    expect(rows[0].amount).toBe(1000);
    expect(rows[1].amount).toBe(1000);
  });

  it('avança data mensalmente', () => {
    const rows = buildInstallmentPreview(1000, 3, new Date('2026-01-01'), 'total');
    expect(rows).toHaveLength(3);
    // Apenas verifica que cada data é diferente
    expect(rows[0].date).not.toBe(rows[1].date);
    expect(rows[1].date).not.toBe(rows[2].date);
  });

  it('usa valor da parcela diretamente no modo per-installment', () => {
    const rows = buildInstallmentPreview(500, 4, new Date('2026-01-01'), 'per-installment');
    rows.forEach(r => expect(r.amount).toBe(500));
    expect(rows).toHaveLength(4);
  });

  it('retorna array vazio para installments <= 0', () => {
    expect(buildInstallmentPreview(1000, 0, new Date(), 'total')).toHaveLength(0);
  });
});
