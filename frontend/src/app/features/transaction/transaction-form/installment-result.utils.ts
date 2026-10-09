export interface InstallmentCreationSummary {
  kind: 'none' | 'partial' | 'complete';
  message: string;
  createdCount: number;
  requestedCount: number;
}

export function summarizeInstallmentCreation(
  createdCount: number,
  requestedCount: number,
): InstallmentCreationSummary {
  if (createdCount === 0) {
    return {
      kind: 'none',
      message: 'Nenhuma parcela foi lançada: todas cairiam em faturas fechadas ou pagas.',
      createdCount,
      requestedCount,
    };
  }

  if (createdCount < requestedCount) {
    return {
      kind: 'partial',
      message: `${createdCount} de ${requestedCount} parcelas criadas. As demais foram ignoradas porque as faturas estão fechadas ou pagas.`,
      createdCount,
      requestedCount,
    };
  }

  return {
    kind: 'complete',
    message: createdCount > 1 ? `${createdCount} parcelas criadas com sucesso!` : 'Transação criada com sucesso!',
    createdCount,
    requestedCount,
  };
}
