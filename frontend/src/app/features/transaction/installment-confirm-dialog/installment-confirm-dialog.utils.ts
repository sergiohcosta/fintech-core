import { InstallmentPreviewDTO } from '../../../core/api/fintechSaaSAPI.schemas';

export interface InstallmentConfirmationRow {
  installmentNumber: number;
  totalInstallments: number;
  amount: string;
  invoice: string;
  dueDate: string;
  willCreate: boolean;
  status: string;
}

export function hasSkippedInstallments(installments: InstallmentPreviewDTO[]): boolean {
  return installments.some((installment) => !installment.willCreate);
}

export function formatInstallmentRows(
  installments: InstallmentPreviewDTO[],
): InstallmentConfirmationRow[] {
  return installments.map((installment) => {
    const invoiceDate = new Date(installment.referenceYear, installment.referenceMonth - 1, 1);
    const dueDate = parseLocalDate(installment.dueDate);
    const invoiceStatus = installment.invoiceStatus;

    return {
      installmentNumber: installment.installmentNumber,
      totalInstallments: installment.totalInstallments,
      amount: new Intl.NumberFormat('pt-BR', {
        style: 'currency',
        currency: 'BRL',
      }).format(installment.amount),
      invoice: new Intl.DateTimeFormat('pt-BR', {
        month: 'long',
        year: 'numeric',
      }).format(invoiceDate),
      dueDate: new Intl.DateTimeFormat('pt-BR').format(dueDate),
      willCreate: installment.willCreate,
      status: installment.willCreate
        ? 'Será criada'
        : invoiceStatus === 'CLOSED'
          ? 'Ignorada · fatura fechada'
          : invoiceStatus === 'PAID'
            ? 'Ignorada · fatura paga'
            : 'Ignorada · fatura fechada/paga',
    };
  });
}

function parseLocalDate(value: string): Date {
  const [year, month, day] = value.split('-').map(Number);
  return new Date(year, month - 1, day);
}
