import { describe, it, expect } from 'vitest';
import { buildInstallmentPreview } from './installment-preview';

describe('buildInstallmentPreview', () => {
  it('retorna vazio para entradas inválidas', () => {
    expect(buildInstallmentPreview(0, 3, new Date(2026, 0, 10), 'total')).toEqual([]);
    expect(buildInstallmentPreview(100, 0, new Date(2026, 0, 10), 'total')).toEqual([]);
  });

  it('divide o total em N parcelas (modo total)', () => {
    const rows = buildInstallmentPreview(300, 3, new Date(2026, 0, 10), 'total');
    expect(rows).toHaveLength(3);
    expect(rows.every((r) => r.amount === 100)).toBe(true);
  });

  // #137 — mês de vencimento com menos dias que dueDay não pode rolar para o mês seguinte.
  // Compra em jan com cartão fecha/vence dia 31: a 2ª parcela cai na fatura de fev, que
  // vence 28/fev (capado), não 03/mar (rollover silencioso do Date do JS).
  it('capa dueDay ao último dia do mês na label de vencimento (fev)', () => {
    const rows = buildInstallmentPreview(200, 2, new Date(2026, 0, 5), 'total', {
      closingDay: 31,
      dueDay: 31,
    });
    // compra dia 5 <= closingDay 31 → 1ª fatura = jan/2026 (vence 31/jan), 2ª = fev/2026
    expect(rows[0].invoiceLabel).toContain('vence 31/01');
    expect(rows[1].invoiceLabel).toContain('vence 28/02');
  });
});
