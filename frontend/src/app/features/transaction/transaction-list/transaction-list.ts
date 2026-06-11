import { Component, inject, OnInit, signal, computed, untracked } from '@angular/core';
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
import { forkJoin } from 'rxjs';

import { TransactionsService } from '../../../core/api/transactions/transactions.service';
import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { InstallmentGroupsService } from '../../../core/api/installment-groups/installment-groups.service';
import { TransfersService } from '../../../core/api/transfers/transfers.service';
import { TransactionResponseDTO, AccountResponse, InvoiceStatus } from '../../../core/api/fintechSaaSAPI.schemas';
import { ConfirmationDialogComponent } from '../../../components/confirmation-dialog/confirmation-dialog';
import { DeleteInstallmentDialogComponent, DeleteInstallmentDialogResult } from './delete-installment-dialog/delete-installment-dialog';
import { TransactionFiltersComponent } from './transaction-filters/transaction-filters';
import { TransactionFilters, DEFAULT_FILTERS, currentMonthFilters } from './transaction-filters/transaction-filters.types';
import { buildDisplayRows, InstallmentGroupInfo, DisplayRow, InvoiceHeaderRow, resolveMonthKey, formatMonthLabel } from './transaction-list.utils';
export { buildDisplayRows } from './transaction-list.utils';
export type { InstallmentGroupInfo, DisplayRow, InvoiceHeaderRow } from './transaction-list.utils';

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
    DatePipe,
    TransactionFiltersComponent,
  ],
  templateUrl: './transaction-list.html',
  styleUrl: './transaction-list.scss'
})
export class TransactionList implements OnInit {
  private service         = inject(TransactionsService);
  private accountService  = inject(AccountsService);
  private groupService    = inject(InstallmentGroupsService);
  private transferService = inject(TransfersService);
  private router   = inject(Router);
  private dialog   = inject(MatDialog);
  private snackBar = inject(MatSnackBar);

  transactions         = signal<TransactionResponseDTO[]>([]);
  accounts             = signal<AccountResponse[]>([]);
  expandedTransactions = signal(new Set<string>());
  filters              = signal<TransactionFilters>(DEFAULT_FILTERS);
  showFilters          = signal(false);

  filteredTransactions = computed(() => {
    const desc = this.filters().description?.toLowerCase().trim();
    if (!desc) return this.transactions();
    return this.transactions().filter(t =>
      t.description?.toLowerCase().includes(desc)
    );
  });

  displayedColumns = ['description', 'amount', 'date', 'type', 'status', 'category', 'account', 'actions'];

  displayRows = computed(() =>
    buildDisplayRows(
      this.filteredTransactions(),
      this.expandedTransactions(),
      this.filters().groupByPeriod,
      this.filters().groupByInvoice,
    )
  );

  activeFilterChips = computed((): Array<{ label: string; field: string; colorClass: string }> => {
    const f = this.filters();
    const chips: Array<{ label: string; field: string; colorClass: string }> = [];
    if (f.accountIds.length === 1) {
      const account = this.accounts().find(a => a.id === f.accountIds[0]);
      chips.push({ label: account?.name ?? 'Conta', field: 'accountIds', colorClass: 'chip-account' });
    } else if (f.accountIds.length > 1) {
      chips.push({ label: `${f.accountIds.length} contas`, field: 'accountIds', colorClass: 'chip-account' });
    }
    if (f.status === 'PENDING')   chips.push({ label: 'Pendente',  field: 'status', colorClass: 'chip-pending' });
    if (f.status === 'PAID')      chips.push({ label: 'Pago',      field: 'status', colorClass: 'chip-paid' });
    if (f.status === 'CANCELLED') chips.push({ label: 'Cancelado', field: 'status', colorClass: 'chip-cancelled' });
    if (f.type === 'EXPENSE') chips.push({ label: 'Despesa', field: 'type', colorClass: 'chip-expense' });
    if (f.type === 'INCOME')  chips.push({ label: 'Receita', field: 'type', colorClass: 'chip-income' });
    if (f.startDate && f.endDate) {
      const key = resolveMonthKey(f.startDate, f.endDate);
      const label = key && key !== 'custom'
        ? formatMonthLabel(key)
        : `${f.startDate} – ${f.endDate}`;
      chips.push({ label, field: 'period', colorClass: 'chip-period' });
    }
    if (f.description) {
      chips.push({ label: `"${f.description}"`, field: 'description', colorClass: 'chip-description' });
    }
    return chips;
  });

  isDataRow       = (_: number, row: DisplayRow) => row.kind === 'single' || row.kind === 'installment';
  isDetailRow     = (_: number, row: DisplayRow) => row.kind === 'installment-detail';
  isPeriodHeader  = (_: number, row: DisplayRow) => row.kind === 'period-header';
  isInvoiceHeader = (_: number, row: DisplayRow) => row.kind === 'invoice-header';

  invoiceHeaderChipClass(status: InvoiceStatus | null): string {
    if (!status) return '';
    const map: Record<InvoiceStatus, string> = { OPEN: 'invoice-open', CLOSED: 'invoice-closed', PAID: 'invoice-paid' };
    return 'invoice-chip ' + (map[status] ?? '');
  }

  invoiceHeaderStatusLabel(status: InvoiceStatus | null): string {
    if (!status) return '';
    const map: Record<InvoiceStatus, string> = { OPEN: 'Aberta', CLOSED: 'Fechada', PAID: 'Paga' };
    return map[status] ?? status;
  }

  ngOnInit(): void {
    const saved = this.loadFromStorage();
    this.filters.set(saved);
    forkJoin({
      accounts:     this.accountService.listAccounts(),
      transactions: this.service.listTransactions({
        accountIds: saved.accountIds.length > 0 ? saved.accountIds : undefined,
        status:    saved.status    ?? undefined,
        type:      saved.type      ?? undefined,
        startDate: saved.startDate ?? undefined,
        endDate:   saved.endDate   ?? undefined,
      }),
    }).subscribe({
      next: ({ accounts, transactions }) => {
        this.accounts.set(accounts);
        this.transactions.set(transactions);
      },
      error: () => this.snackBar.open('Erro ao carregar dados.', 'Fechar', { duration: 5000 }),
    });
  }

  toggleFilters(): void {
    this.showFilters.update(v => !v);
  }

  onFilterChange(newFilters: TransactionFilters): void {
    const prev = this.filters();
    this.filters.set(newFilters);
    this.saveToStorage(newFilters);
    const prevServer = { ...prev,       description: null, groupByPeriod: false, groupByInvoice: false };
    const newServer  = { ...newFilters, description: null, groupByPeriod: false, groupByInvoice: false };
    if (JSON.stringify(prevServer) !== JSON.stringify(newServer)) {
      untracked(() => this.loadTransactions(newFilters));
    }
  }

  clearFilterChip(field: string): void {
    this.filters.update(f => {
      if (field === 'accountIds')  return { ...f, accountIds: [] };
      if (field === 'status')      return { ...f, status: null };
      if (field === 'type')        return { ...f, type: null };
      if (field === 'period')      return { ...f, startDate: null, endDate: null };
      if (field === 'description') return { ...f, description: null };
      return f;
    });
    if (field !== 'description') {
      untracked(() => this.loadTransactions(this.filters()));
    }
  }

  loadTransactions(f: TransactionFilters = this.filters()): void {
    this.service.listTransactions({
      accountIds: f.accountIds.length > 0 ? f.accountIds : undefined,
      status:    f.status     ?? undefined,
      type:      f.type       ?? undefined,
      startDate: f.startDate  ?? undefined,
      endDate:   f.endDate    ?? undefined,
    }).subscribe({
      next:  (data) => this.transactions.set(data),
      error: () => this.snackBar.open('Erro ao carregar transações.', 'Fechar', { duration: 5000 }),
    });
  }

  toggleExpand(id: string): void {
    this.expandedTransactions.update(set => {
      const next = new Set(set);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  }

  onEdit(t: TransactionResponseDTO | undefined): void {
    if (!t) return;
    this.router.navigate(['/transactions', t.id]);
  }

  onDeleteGroup(group: InstallmentGroupInfo, event: Event): void {
    event.stopPropagation();
    const dialogRef = this.dialog.open(ConfirmationDialogComponent, {
      width: '400px',
      data: {
        title: 'Excluir grupo de parcelamento',
        message: `Deseja excluir o grupo "${group.description}"? Parcelas já pagas serão mantidas no histórico.`,
        confirmText: 'Sim, excluir pendentes',
      },
    });
    dialogRef.afterClosed().subscribe(confirmed => {
      if (confirmed !== true) return;
      this.groupService.deleteInstallmentGroup(group.groupId).subscribe({
        next: (result) => {
          const msg = result.skippedPaid > 0
            ? `${result.deleted} parcela(s) excluída(s). ${result.skippedPaid} pagas foram mantidas.`
            : `${result.deleted} parcela(s) excluída(s).`;
          this.snackBar.open(msg, 'OK', { duration: 4000 });
          this.loadTransactions();
        },
        error: () => this.snackBar.open('Erro ao excluir grupo.', 'Fechar', { duration: 5000 }),
      });
    });
  }

  onDelete(t: TransactionResponseDTO | undefined): void {
    if (!t) return;
    const isInstallment = !!t.installmentGroupId;
    const isTransfer    = !!t.transferId;

    if (!isTransfer && isInstallment) {
      const dialogRef = this.dialog.open(DeleteInstallmentDialogComponent, {
        width: '460px',
        data: { transaction: t },
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
          error: () => this.snackBar.open('Erro ao excluir parcela.', 'Fechar', { duration: 5000 }),
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
          confirmText: 'Sim, excluir',
        },
      });
      dialogRef.afterClosed().subscribe(confirmed => {
        if (confirmed !== true) return;
        this.transferService.deleteTransfer(t.transferId!).subscribe({
          next: () => { this.snackBar.open('Transferência excluída.', 'OK', { duration: 3000 }); this.loadTransactions(); },
          error: () => this.snackBar.open('Erro ao excluir transferência.', 'Fechar', { duration: 5000 }),
        });
      });
      return;
    }

    const dialogRef = this.dialog.open(ConfirmationDialogComponent, {
      width: '400px',
      data: {
        title: 'Excluir Transação',
        message: `Deseja excluir "${t.description}"? Esta ação não pode ser desfeita.`,
        confirmText: 'Sim, excluir',
      },
    });
    dialogRef.afterClosed().subscribe(confirmed => {
      if (confirmed !== true) return;
      this.service.deleteTransaction(t.id).subscribe({
        next: () => { this.snackBar.open('Transação excluída.', 'OK', { duration: 3000 }); this.loadTransactions(); },
        error: () => this.snackBar.open('Erro ao excluir transação.', 'Fechar', { duration: 5000 }),
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
    const map: Record<string, string> = { OPEN: 'invoice-open', CLOSED: 'invoice-closed', PAID: 'invoice-paid' };
    return 'invoice-chip ' + (map[status ?? ''] ?? '');
  }

  invoiceLabel(t: TransactionResponseDTO | undefined): string | null {
    if (!t?.invoiceId || !t.invoiceDueDate) return null;
    const d = new Date(t.invoiceDueDate + 'T00:00:00');
    const month = d.toLocaleDateString('pt-BR', { month: 'short', year: 'numeric' });
    return `Fatura ${month}`;
  }

  private loadFromStorage(): TransactionFilters {
    try {
      const raw = localStorage.getItem('fintech.transaction.filters');
      if (!raw) return currentMonthFilters();
      const parsed = JSON.parse(raw);
      return { ...DEFAULT_FILTERS, ...parsed, description: null };
    } catch {
      return currentMonthFilters();
    }
  }

  private saveToStorage(f: TransactionFilters): void {
    try {
      const { description: _desc, ...toSave } = f; // descrição nunca é persistida
      localStorage.setItem('fintech.transaction.filters', JSON.stringify(toSave));
    } catch {
      // localStorage pode estar cheio ou bloqueado — ignorar silenciosamente
    }
  }
}
