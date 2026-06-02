export interface InstallmentPreviewRow {
  number: number;
  date: string;
  invoiceLabel?: string;
  amount: number;
}

export interface CreditCardPreviewConfig {
  closingDay: number;
  dueDay: number;
}

const MONTH_ABBR = ['Jan', 'Fev', 'Mar', 'Abr', 'Mai', 'Jun',
                    'Jul', 'Ago', 'Set', 'Out', 'Nov', 'Dez'];

export function buildInstallmentPreview(
  totalAmount: number,
  installments: number,
  startDate: Date,
  valueMode: 'total' | 'per-installment',
  creditCard?: CreditCardPreviewConfig
): InstallmentPreviewRow[] {
  if (installments <= 0 || totalAmount <= 0) return [];

  const installmentAmount = valueMode === 'total'
    ? Math.round((totalAmount / installments) * 100) / 100
    : totalAmount;

  return Array.from({ length: installments }, (_, i) => {
    const d = new Date(startDate);
    d.setMonth(d.getMonth() + i);

    const row: InstallmentPreviewRow = {
      number: i + 1,
      date: d.toLocaleDateString('pt-BR'),
      amount: installmentAmount
    };

    if (creditCard) {
      const invoiceBase = resolveInvoiceMonth(startDate, creditCard.closingDay);
      const targetMonth = new Date(invoiceBase.getFullYear(), invoiceBase.getMonth() + i, 1);
      const dueDate = calcDueDate(targetMonth, creditCard.closingDay, creditCard.dueDay);
      const mm = String(dueDate.getMonth() + 1).padStart(2, '0');
      const dd = String(dueDate.getDate()).padStart(2, '0');
      row.invoiceLabel =
        `${MONTH_ABBR[targetMonth.getMonth()]}/${targetMonth.getFullYear()} · vence ${dd}/${mm}`;
    }

    return row;
  });
}

function resolveInvoiceMonth(purchaseDate: Date, closingDay: number): Date {
  if (purchaseDate.getDate() <= closingDay) {
    return new Date(purchaseDate.getFullYear(), purchaseDate.getMonth(), 1);
  }
  return new Date(purchaseDate.getFullYear(), purchaseDate.getMonth() + 1, 1);
}

function calcDueDate(invoiceMonth: Date, closingDay: number, dueDay: number): Date {
  if (dueDay >= closingDay) {
    return new Date(invoiceMonth.getFullYear(), invoiceMonth.getMonth(), dueDay);
  }
  return new Date(invoiceMonth.getFullYear(), invoiceMonth.getMonth() + 1, dueDay);
}
