import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { TransactionsService } from '../../../core/api/transactions/transactions.service';
import { InstallmentGroupsService } from '../../../core/api/installment-groups/installment-groups.service';
import { TransfersService } from '../../../core/api/transfers/transfers.service';
import { TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';
import { ConfirmationDialogComponent } from '../../../components/confirmation-dialog/confirmation-dialog';

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

@Component({
  selector: 'app-transaction-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatDialogModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatProgressBarModule,
    CurrencyPipe,
    DatePipe
  ],
  templateUrl: './transaction-list.html',
  styleUrl: './transaction-list.scss'
})
export class TransactionList implements OnInit {
  private service = inject(TransactionsService);
  private groupService = inject(InstallmentGroupsService);
  private transferService = inject(TransfersService);
  private router = inject(Router);
  private dialog = inject(MatDialog);
  private snackBar = inject(MatSnackBar);

  transactions = signal<TransactionResponseDTO[]>([]);
  expandedGroups = signal(new Set<string>());

  displayedColumns = ['description', 'amount', 'date', 'type', 'status', 'category', 'account', 'actions'];
  groupColumns = ['group-header'];

  displayRows = computed(() => buildDisplayRows(this.transactions(), this.expandedGroups()));

  isGroupRow = (_: number, row: DisplayRow) => row.kind === 'group';
  isDataRow = (_: number, row: DisplayRow) => row.kind === 'transaction' || row.kind === 'single';

  ngOnInit(): void {
    this.loadTransactions();
  }

  loadTransactions(): void {
    this.service.listTransactions().subscribe({
      next: (data) => this.transactions.set(data),
      error: () => this.snackBar.open('Erro ao carregar transações.', 'Fechar', { duration: 5000 })
    });
  }

  toggleGroup(groupId: string): void {
    this.expandedGroups.update(set => {
      const next = new Set(set);
      next.has(groupId) ? next.delete(groupId) : next.add(groupId);
      return next;
    });
  }

  onEdit(t: TransactionResponseDTO | undefined): void {
    if (!t) return;
    this.router.navigate(['/transactions', t.id]);
  }

  onDeleteGroup(row: GroupRow): void {
    const dialogRef = this.dialog.open(ConfirmationDialogComponent, {
      width: '400px',
      data: {
        title: 'Excluir grupo de parcelamento',
        message: `Deseja excluir o grupo "${row.description}"? Parcelas já pagas serão mantidas no histórico.`,
        confirmText: 'Sim, excluir pendentes'
      }
    });
    dialogRef.afterClosed().subscribe(confirmed => {
      if (confirmed !== true) return;
      this.groupService.deleteInstallmentGroup(row.groupId).subscribe({
        next: (result) => {
          const msg = result.skippedPaid > 0
            ? `${result.deleted} parcela(s) excluída(s). ${result.skippedPaid} pagas foram mantidas.`
            : `${result.deleted} parcela(s) excluída(s).`;
          this.snackBar.open(msg, 'OK', { duration: 4000 });
          this.loadTransactions();
        },
        error: () => this.snackBar.open('Erro ao excluir grupo.', 'Fechar', { duration: 5000 })
      });
    });
  }

  onDelete(t: TransactionResponseDTO | undefined): void {
    if (!t) return;
    const isTransfer = !!t.transferId;

    if (isTransfer) {
      const dialogRef = this.dialog.open(ConfirmationDialogComponent, {
        width: '400px',
        data: {
          title: 'Excluir Transferência',
          message: 'Deseja excluir esta transferência? Os dois lançamentos serão removidos.',
          confirmText: 'Sim, excluir'
        }
      });
      dialogRef.afterClosed().subscribe(confirmed => {
        if (confirmed !== true) return;
        this.transferService.deleteTransfer(t.transferId!).subscribe({
          next: () => { this.snackBar.open('Transferência excluída.', 'OK', { duration: 3000 }); this.loadTransactions(); },
          error: () => this.snackBar.open('Erro ao excluir transferência.', 'Fechar', { duration: 5000 })
        });
      });
      return;
    }

    const dialogRef = this.dialog.open(ConfirmationDialogComponent, {
      width: '400px',
      data: {
        title: 'Excluir Transação',
        message: `Deseja excluir "${t.description}"? Esta ação não pode ser desfeita.`,
        confirmText: 'Sim, excluir'
      }
    });
    dialogRef.afterClosed().subscribe(confirmed => {
      if (confirmed !== true) return;
      this.service.deleteTransaction(t.id).subscribe({
        next: () => { this.snackBar.open('Transação excluída.', 'OK', { duration: 3000 }); this.loadTransactions(); },
        error: () => this.snackBar.open('Erro ao excluir transação.', 'Fechar', { duration: 5000 })
      });
    });
  }

  typeLabel(t: TransactionResponseDTO | undefined): string {
    if (!t) return '';
    if (t.transferId) return 'Transferência';
    const labels: Record<string, string> = { INCOME: 'Receita', EXPENSE: 'Despesa' };
    return labels[t.type ?? ''] ?? (t.type ?? '');
  }

  statusLabel(status: string): string {
    const labels: Record<string, string> = { PENDING: 'Pendente', PAID: 'Pago', CANCELLED: 'Cancelado' };
    return labels[status] ?? status;
  }
}
