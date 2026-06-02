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
import { DeleteInstallmentDialogComponent, DeleteInstallmentDialogResult } from './delete-installment-dialog/delete-installment-dialog';
import { buildDisplayRows, GroupRow, DisplayRow } from './transaction-list.utils';
export { buildDisplayRows } from './transaction-list.utils';
export type { GroupRow, DisplayRow } from './transaction-list.utils';

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
    const isInstallment = !!t.installmentGroupId;
    const isTransfer = !!t.transferId;

    if (!isTransfer && isInstallment) {
      const dialogRef = this.dialog.open(DeleteInstallmentDialogComponent, {
        width: '460px',
        data: { transaction: t }
      });
      dialogRef.afterClosed().subscribe((result: DeleteInstallmentDialogResult | undefined) => {
        if (!result) return;
        this.service.deleteTransaction(t.id, { scope: result.scope }).subscribe({
          next: (res: any) => {
            const msg = res?.skippedPaid > 0
              ? `${res.deleted} parcela(s) excluída(s). ${res.skippedPaid} pagas foram mantidas.`
              : `${res?.deleted ?? 1} parcela(s) excluída(s).`;
            this.snackBar.open(msg, 'OK', { duration: 4000 });
            this.loadTransactions();
          },
          error: () => this.snackBar.open('Erro ao excluir parcela.', 'Fechar', { duration: 5000 })
        });
      });
      return;
    }

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

  invoiceChipClass(status: string | undefined): string {
    const map: Record<string, string> = {
      OPEN: 'invoice-open',
      CLOSED: 'invoice-closed',
      PAID: 'invoice-paid'
    };
    return 'invoice-chip ' + (map[status ?? ''] ?? '');
  }

  invoiceLabel(t: TransactionResponseDTO | undefined): string | null {
    if (!t?.invoiceId || !t.invoiceDueDate) return null;
    const d = new Date(t.invoiceDueDate + 'T00:00:00');
    const month = d.toLocaleDateString('pt-BR', { month: 'short', year: 'numeric' });
    return `Fatura ${month}`;
  }
}
