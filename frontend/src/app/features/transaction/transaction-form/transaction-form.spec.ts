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

describe('buildInstallmentPreview — CREDIT_CARD', () => {
  const creditCard = { closingDay: 5, dueDay: 15 };

  it('compra antes do fechamento: parcela 1 vai para o mesmo mês', () => {
    const rows = buildInstallmentPreview(300, 1, new Date('2026-06-03'), 'total', creditCard);
    expect(rows[0].invoiceLabel).toContain('Jun/2026');
  });

  it('compra após fechamento: parcela 1 vai para o mês seguinte', () => {
    const rows = buildInstallmentPreview(300, 1, new Date('2026-06-08'), 'total', creditCard);
    expect(rows[0].invoiceLabel).toContain('Jul/2026');
  });

  it('3 parcelas: cada uma em uma fatura diferente', () => {
    const rows = buildInstallmentPreview(900, 3, new Date('2026-06-03'), 'total', creditCard);
    expect(rows[0].invoiceLabel).toContain('Jun/2026');
    expect(rows[1].invoiceLabel).toContain('Jul/2026');
    expect(rows[2].invoiceLabel).toContain('Ago/2026');
  });

  it('vencimento no mesmo mês quando dueDay >= closingDay', () => {
    const rows = buildInstallmentPreview(100, 1, new Date('2026-06-03'), 'total', creditCard);
    expect(rows[0].invoiceLabel).toContain('vence 15/06');
  });

  it('vencimento no mês seguinte quando dueDay < closingDay', () => {
    const cc = { closingDay: 25, dueDay: 5 };
    const rows = buildInstallmentPreview(100, 1, new Date('2026-12-10'), 'total', cc);
    expect(rows[0].invoiceLabel).toContain('Dez/2026');
    expect(rows[0].invoiceLabel).toContain('vence 05/01');
  });

  it('sem creditCard: invoiceLabel é undefined', () => {
    const rows = buildInstallmentPreview(300, 2, new Date('2026-06-01'), 'total');
    rows.forEach(r => expect(r.invoiceLabel).toBeUndefined());
  });
});
