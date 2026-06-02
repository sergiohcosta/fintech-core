import { TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

export type InstallmentGroupInfo = {
  groupId: string;
  description: string;
  totalInstallments: number;
  paidInstallments: number;
  installmentAmount: number;
  categoryName: string | null;
  accountName: string | null;
  transactions: TransactionResponseDTO[];
};

export type DisplayRow =
  | { kind: 'single';             data: TransactionResponseDTO }
  | { kind: 'installment';        data: TransactionResponseDTO; group: InstallmentGroupInfo; isExpanded: boolean }
  | { kind: 'installment-detail'; data: TransactionResponseDTO; group: InstallmentGroupInfo };

export function buildDisplayRows(
  transactions: TransactionResponseDTO[],
  expandedIds: Set<string>
): DisplayRow[] {
  const groupsMap = new Map<string, TransactionResponseDTO[]>();
  for (const t of transactions) {
    if (t.installmentGroupId) {
      const existing = groupsMap.get(t.installmentGroupId) ?? [];
      existing.push(t);
      groupsMap.set(t.installmentGroupId, existing);
    }
  }

  const groupInfoMap = new Map<string, InstallmentGroupInfo>();
  for (const [groupId, txs] of groupsMap) {
    groupInfoMap.set(groupId, {
      groupId,
      description: txs[0]?.installmentGroupDescription ?? txs[0]?.description ?? '',
      totalInstallments: txs.length,
      paidInstallments: txs.filter(tx => tx.status === 'PAID').length,
      installmentAmount: txs[0]?.amount ?? 0,
      categoryName: txs[0]?.categoryName ?? null,
      accountName: txs[0]?.accountName ?? null,
      transactions: txs,
    });
  }

  return transactions.flatMap(t => {
    if (t.installmentGroupId) {
      const group = groupInfoMap.get(t.installmentGroupId)!;
      const isExpanded = expandedIds.has(t.id);
      const rows: DisplayRow[] = [{ kind: 'installment', data: t, group, isExpanded }];
      if (isExpanded) {
        rows.push({ kind: 'installment-detail', data: t, group });
      }
      return rows;
    }
    return [{ kind: 'single', data: t }];
  });
}
