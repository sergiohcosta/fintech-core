import { describe, expect, it } from 'vitest';
import { InstallmentPreviewDTO } from '../../../core/api/fintechSaaSAPI.schemas';
import { formatInstallmentRows, hasSkippedInstallments } from './installment-confirm-dialog.utils';

const installment = (overrides: Partial<InstallmentPreviewDTO> = {}): InstallmentPreviewDTO => ({
  installmentNumber: 1,
  totalInstallments: 3,
  amount: 125.5,
  referenceYear: 2026,
  referenceMonth: 6,
  closingDate: '2026-06-05',
  dueDate: '2026-06-15',
  invoiceStatus: 'OPEN',
  willCreate: true,
  ...overrides,
});

describe('installment confirmation helpers', () => {
  it('só pede confirmação quando ao menos uma parcela será ignorada', () => {
    expect(hasSkippedInstallments([installment(), installment({ installmentNumber: 2 })])).toBe(false);
    expect(hasSkippedInstallments([installment({ willCreate: false, invoiceStatus: 'PAID' })])).toBe(true);
    expect(hasSkippedInstallments([])).toBe(false);
  });

  it('formata parcela, fatura, vencimento e situação para a lista do diálogo', () => {
    expect(
      formatInstallmentRows([
        installment({ installmentNumber: 3, willCreate: false, invoiceStatus: 'CLOSED' }),
      ]),
    ).toEqual([
      {
        installmentNumber: 3,
        totalInstallments: 3,
        amount: 'R$ 125,50',
        invoice: 'junho de 2026',
        dueDate: '15/06/2026',
        willCreate: false,
        status: 'Ignorada · fatura fechada',
      },
    ]);
  });
});
