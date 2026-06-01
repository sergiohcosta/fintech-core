export interface InstallmentPreviewRow {
  number: number;
  date: string;
  amount: number;
}

export function buildInstallmentPreview(
  totalAmount: number,
  installments: number,
  startDate: Date,
  valueMode: 'total' | 'per-installment'
): InstallmentPreviewRow[] {
  if (installments <= 0 || totalAmount <= 0) return [];
  const installmentAmount = valueMode === 'total'
    ? Math.round((totalAmount / installments) * 100) / 100
    : totalAmount;

  return Array.from({ length: installments }, (_, i) => {
    const d = new Date(startDate);
    d.setMonth(d.getMonth() + i);
    return {
      number: i + 1,
      date: d.toLocaleDateString('pt-BR'),
      amount: installmentAmount
    };
  });
}
