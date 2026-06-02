import { TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

export type GroupRow = {
  kind: 'group';
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
  | GroupRow
  | { kind: 'transaction'; data: TransactionResponseDTO }
  | { kind: 'single'; data: TransactionResponseDTO };

export function buildDisplayRows(
  transactions: TransactionResponseDTO[],
  expandedGroups: Set<string>
): DisplayRow[] {
  const result: DisplayRow[] = [];
  const seenGroups = new Set<string>();
  const groupsMap = new Map<string, TransactionResponseDTO[]>();

  for (const t of transactions) {
    if (t.installmentGroupId) {
      const existing = groupsMap.get(t.installmentGroupId) ?? [];
      existing.push(t);
      groupsMap.set(t.installmentGroupId, existing);
    }
  }

  for (const t of transactions) {
    if (t.installmentGroupId) {
      if (!seenGroups.has(t.installmentGroupId)) {
        seenGroups.add(t.installmentGroupId);
        const groupTxs = groupsMap.get(t.installmentGroupId)!;
        const paidCount = groupTxs.filter(tx => tx.status === 'PAID').length;
        result.push({
          kind: 'group',
          groupId: t.installmentGroupId,
          description: t.installmentGroupDescription ?? t.description ?? '',
          totalInstallments: groupTxs.length,
          paidInstallments: paidCount,
          installmentAmount: groupTxs[0]?.amount ?? 0,
          categoryName: t.categoryName ?? null,
          accountName: t.accountName ?? null,
          transactions: groupTxs
        });
        if (expandedGroups.has(t.installmentGroupId)) {
          groupTxs.forEach(tx => result.push({ kind: 'transaction', data: tx }));
        }
      }
    } else {
      result.push({ kind: 'single', data: t });
    }
  }
  return result;
}
