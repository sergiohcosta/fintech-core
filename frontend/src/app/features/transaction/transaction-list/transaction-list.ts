import { Component, inject, OnInit, signal, computed, effect, untracked } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { Router, RouterLink, ActivatedRoute } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { forkJoin, map, catchError, of, Observable } from 'rxjs';

import { TransactionsService } from '../../../core/api/transactions/transactions.service';
import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { InstallmentGroupsService } from '../../../core/api/installment-groups/installment-groups.service';
import { TransfersService } from '../../../core/api/transfers/transfers.service';
import { InvoicesService } from '../../../core/api/invoices/invoices.service';
import { TransactionResponseDTO, AccountResponse, InvoiceResponseDTO, InvoiceStatus } from '../../../core/api/fintechSaaSAPI.schemas';
import { ConfirmationDialogComponent } from '../../../components/confirmation-dialog/confirmation-dialog';
import { DeleteInstallmentDialogComponent, DeleteInstallmentDialogResult } from './delete-installment-dialog/delete-installment-dialog';
import { TransactionFiltersComponent } from './transaction-filters/transaction-filters';
import { TransactionFilters, DEFAULT_FILTERS, currentMonthFilters, TransactionType, TransactionStatus } from './transaction-filters/transaction-filters.types';
import { buildDisplayRows, InstallmentGroupInfo, DisplayRow, InvoiceSummaryRow, resolveMonthKey, formatMonthLabel, SortCol, SortCriterion, applySort, getSortInfo, isGhost, exportToCsv, selectableRowId, planBulkDelete } from './transaction-list.utils';
import { RecurrenceService } from '../../../core/services/recurrence.service';
import { triggerCsvDownload } from '../../../core/csv.utils';
export { buildDisplayRows } from './transaction-list.utils';
export type { InstallmentGroupInfo, DisplayRow, InvoiceSummaryRow, SortCriterion } from './transaction-list.utils';

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
    MatCheckboxModule,
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
  private invoiceService  = inject(InvoicesService);
  private recurrenceService = inject(RecurrenceService);
  private router   = inject(Router);
  private route    = inject(ActivatedRoute);
  private dialog   = inject(MatDialog);
  private snackBar = inject(MatSnackBar);

  transactions         = signal<TransactionResponseDTO[]>([]);
  accounts             = signal<AccountResponse[]>([]);
  expandedTransactions = signal(new Set<string>());
  filters              = signal<TransactionFilters>(DEFAULT_FILTERS);
  showFilters          = signal(false);
  sortCriteria         = signal<SortCriterion[]>([{ col: 'date', dir: 'desc' }]);
  invoicesForGrouping  = signal<InvoiceResponseDTO[]>([]);
  selectedIds          = signal<Set<string>>(new Set());

  constructor() {
    effect(() => {
      const f = this.filters();
      if (!f.groupByInvoice || !f.startDate || !f.endDate) {
        this.invoicesForGrouping.set([]);
        return;
      }
      const allAccounts = this.accounts();
      if (!allAccounts.length) return;

      const ccAccounts = allAccounts
        .filter(a => a.type === 'CREDIT_CARD' && (f.accountIds.length === 0 || f.accountIds.includes(a.id)));

      if (!ccAccounts.length) {
        this.invoicesForGrouping.set([]);
        return;
      }

      const [sy, sm] = f.startDate!.split('-').map(Number);
      const [ey, em] = f.endDate!.split('-').map(Number);
      const startOrd = sy * 12 + sm;
      const endOrd   = ey * 12 + em;

      forkJoin(ccAccounts.map(a => this.invoiceService.listInvoices({ accountId: a.id }))).subscribe({
        next: (results) => {
          const filtered = results.flat().filter(inv => {
            const ord = inv.referenceYear * 12 + inv.referenceMonth;
            return ord >= startOrd && ord <= endOrd;
          });
          untracked(() => this.invoicesForGrouping.set(filtered));
        },
      });
    });
  }

  filteredTransactions = computed(() => {
    const f = this.filters();
    let txs = this.transactions();

    // Quando agrupando por fatura, o backend filtra transações avulsas de cartão por t.date,
    // mas a referência financeira correta é invoice.dueDate. Refiltramos aqui para evitar
    // que uma compra feita após o fechamento apareça numa fatura do mês seguinte ao filtrado.
    if (f.groupByInvoice && f.startDate && f.endDate) {
      txs = txs.filter(t => {
        if (!t.invoiceId || !t.invoiceDueDate) return true;
        return t.invoiceDueDate >= f.startDate! && t.invoiceDueDate <= f.endDate!;
      });
    }

    const desc = f.description?.toLowerCase().trim();
    if (!desc) return txs;
    return txs.filter(t => t.description?.toLowerCase().includes(desc));
  });

  displayedColumns = ['select', 'description', 'amount', 'date', 'type', 'status', 'category', 'account', 'actions'];

  displayRows = computed(() =>
    buildDisplayRows(
      this.filteredTransactions(),
      this.expandedTransactions(),
      this.filters().groupByPeriod,
      this.filters().groupByInvoice,
      this.sortCriteria(),
      this.invoicesForGrouping(),
    )
  );

  // Ids selecionáveis na visão atual (regra em selectableRowId: single real + parcelas, nunca
  // fantasma/detail/summary/header) — base pro "selecionar tudo" e pro plano de exclusão em lote.
  selectableRowIds = computed(() =>
    this.displayRows()
      .map(row => selectableRowId(row))
      .filter((id): id is string => id !== null)
  );

  selectedCount = computed(() => this.selectedIds().size);

  allSelected = computed(() => {
    const ids = this.selectableRowIds();
    return ids.length > 0 && ids.every(id => this.selectedIds().has(id));
  });

  activeFilterChips = computed((): Array<{ label: string; field: string; colorClass: string }> => {
    const f = this.filters();
    const chips: Array<{ label: string; field: string; colorClass: string }> = [];
    if (f.accountIds.length === 1) {
      const account = this.accounts().find(a => a.id === f.accountIds[0]);
      chips.push({ label: account?.name ?? 'Conta', field: 'accountIds', colorClass: 'chip-account' });
    } else if (f.accountIds.length > 1) {
      chips.push({ label: `${f.accountIds.length} contas`, field: 'accountIds', colorClass: 'chip-account' });
    }
    const statusLabels: Record<string, string> = { PENDING: 'Pendente', PAID: 'Pago', CANCELLED: 'Cancelado' };
    const statusColors: Record<string, string> = { PENDING: 'chip-pending', PAID: 'chip-paid', CANCELLED: 'chip-cancelled' };
    f.statuses.forEach(s => chips.push({ label: statusLabels[s] ?? s, field: 'statuses', colorClass: statusColors[s] ?? 'chip-pending' }));
    const typeLabels: Record<string, string> = { EXPENSE: 'Despesa', INCOME: 'Receita', TRANSFER: 'Transferência' };
    f.types.forEach(t => chips.push({ label: typeLabels[t] ?? t, field: 'types', colorClass: t === 'INCOME' ? 'chip-income' : 'chip-expense' }));
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

  isDataRow      = (_: number, row: DisplayRow) => row.kind === 'single' || row.kind === 'installment' || row.kind === 'invoice-summary';
  isDetailRow    = (_: number, row: DisplayRow) => row.kind === 'installment-detail';
  isPeriodHeader = (_: number, row: DisplayRow) => row.kind === 'period-header';

  onSortClick(col: SortCol, event: MouseEvent): void {
    this.sortCriteria.update(criteria => applySort(criteria, col, event.shiftKey));
  }

  sortInfo(col: SortCol): { priority: number; dir: 'asc' | 'desc' } | null {
    return getSortInfo(this.sortCriteria(), col);
  }

  invoiceStatusChipClass(status: InvoiceStatus): string {
    const map: Record<InvoiceStatus, string> = { OPEN: 'invoice-open', CLOSED: 'invoice-closed', PAID: 'invoice-paid' };
    return 'invoice-chip ' + (map[status] ?? '');
  }

  invoiceStatusLabel(status: InvoiceStatus): string {
    const map: Record<InvoiceStatus, string> = { OPEN: 'Aberta', CLOSED: 'Fechada', PAID: 'Paga' };
    return map[status] ?? status;
  }

  // Mescla queryParams (vindos da timeline via "Ver lista") sobre uma base de filtros.
  // queryParams têm prioridade: o usuário pediu explicitamente esses filtros pela URL.
  // Estática e pura → testável no Vitest sem TestBed.
  static mergeFiltersFromQueryParams(
    base: TransactionFilters,
    qp: Record<string, string | undefined>,
  ): TransactionFilters {
    const next = { ...base };
    if (qp['accountIds']) next.accountIds = qp['accountIds'].split(',').filter(Boolean);
    if (qp['status'])     next.statuses   = [qp['status'] as TransactionStatus];
    if (qp['type'])       next.types      = [qp['type'] as TransactionType];
    if (qp['startDate'])  next.startDate  = qp['startDate'];
    if (qp['endDate'])    next.endDate    = qp['endDate'];
    if (qp['description']) next.description = qp['description'];
    return next;
  }

  ngOnInit(): void {
    const saved = this.loadFromStorage();
    // Snapshot (leitura única na entrada): se a timeline mandou filtros pela URL, eles vencem.
    const qp = this.route.snapshot.queryParams as Record<string, string | undefined>;
    const initial = TransactionList.mergeFiltersFromQueryParams(saved, qp);
    this.filters.set(initial);
    forkJoin({
      accounts:     this.accountService.listAccounts(),
      transactions: this.service.listTransactions({
        accountIds: initial.accountIds.length > 0 ? initial.accountIds : undefined,
        status:    initial.statuses[0],
        type:      initial.types.find((t): t is 'INCOME' | 'EXPENSE' => t === 'INCOME' || t === 'EXPENSE'),
        startDate: initial.startDate ?? undefined,
        endDate:   initial.endDate   ?? undefined,
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
    this.clearSelection();
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
    this.clearSelection();
    this.filters.update(f => {
      if (field === 'accountIds')  return { ...f, accountIds: [] };
      if (field === 'statuses')    return { ...f, statuses: [] };
      if (field === 'types')       return { ...f, types: [] };
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
      status:    f.statuses[0],
      type:      f.types.find((t): t is 'INCOME' | 'EXPENSE' => t === 'INCOME' || t === 'EXPENSE'),
      startDate: f.startDate  ?? undefined,
      endDate:   f.endDate    ?? undefined,
      // O backend só projeta quando há janela (startDate+endDate); sem período, retorna só reais.
      includeProjected: true,
    }).subscribe({
      next:  (data) => this.transactions.set(data.map(t => this.withGhostKey(t))),
      error: () => this.snackBar.open('Erro ao carregar transações.', 'Fechar', { duration: 5000 }),
    });
  }

  // Fantasmas vêm com id null. Atribui uma chave sintética estável para que dedup/track/expand
  // (todos keyed por id) funcionem. Confirmar/Pular usam recurrenceRuleId+occurrenceDate, não o id.
  private withGhostKey(t: TransactionResponseDTO): TransactionResponseDTO {
    return isGhost(t) && !t.id
      ? { ...t, id: `ghost:${t.recurrenceRuleId}:${t.occurrenceDate}` }
      : t;
  }

  isGhost = isGhost;

  onConfirmGhost(t: TransactionResponseDTO | undefined): void {
    if (!t?.recurrenceRuleId || !t.occurrenceDate) return;
    // MVP: confirma com o valor base/data da ocorrência. Ajuste de valor antes de confirmar
    // (dialog) fica para um refinamento — o usuário pode editar a transação materializada.
    this.recurrenceService.confirm(t.recurrenceRuleId, t.occurrenceDate, {}).subscribe({
      next: () => { this.snackBar.open('Ocorrência confirmada.', 'OK', { duration: 3000 }); this.loadTransactions(); },
      error: () => this.snackBar.open('Erro ao confirmar ocorrência.', 'Fechar', { duration: 5000 }),
    });
  }

  onSkipGhost(t: TransactionResponseDTO | undefined): void {
    if (!t?.recurrenceRuleId || !t.occurrenceDate) return;
    this.recurrenceService.skip(t.recurrenceRuleId, t.occurrenceDate).subscribe({
      next: () => { this.snackBar.open('Ocorrência pulada.', 'OK', { duration: 3000 }); this.loadTransactions(); },
      error: () => this.snackBar.open('Erro ao pular ocorrência.', 'Fechar', { duration: 5000 }),
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

  isSelected(id: string | null | undefined): boolean {
    return !!id && this.selectedIds().has(id);
  }

  toggleSelect(id: string | null | undefined): void {
    if (!id) return;
    this.selectedIds.update(set => {
      const next = new Set(set);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  }

  toggleSelectAll(): void {
    this.selectedIds.set(this.allSelected() ? new Set() : new Set(this.selectableRowIds()));
  }

  clearSelection(): void {
    this.selectedIds.set(new Set());
  }

  // Cada linha selecionada já sabe sua própria regra de exclusão (avulsa/parcela vs. perna de
  // transferência — ver planBulkDelete). Falha individual não aborta o lote: cada chamada vira
  // {success,reason} via catchError antes do forkJoin, senão 1 transação bloqueada (ex.: vinculada
  // a item de planejamento, 400) derrubaria a exclusão das outras N-1 que eram válidas.
  bulkDelete(): void {
    const plan = planBulkDelete(this.displayRows(), this.selectedIds());
    const total = plan.transactionIds.length + plan.transferIds.length;
    if (total === 0) return;

    const dialogRef = this.dialog.open(ConfirmationDialogComponent, {
      width: '400px',
      data: {
        title: 'Excluir transações selecionadas',
        message: `Deseja excluir ${this.selectedCount()} transação(ões) selecionada(s)? Esta ação não pode ser desfeita.`,
        confirmText: 'Sim, excluir',
      },
    });

    dialogRef.afterClosed().subscribe(confirmed => {
      if (confirmed !== true) return;

      type DeleteResult = { success: true } | { success: false; reason: string };
      const safeDelete = <T>(obs: Observable<T>, fallback: string): Observable<DeleteResult> =>
        obs.pipe(
          map((): DeleteResult => ({ success: true })),
          catchError(err => of<DeleteResult>({ success: false, reason: err.error?.message ?? fallback })),
        );

      const deletes$ = [
        ...plan.transactionIds.map(id =>
          safeDelete(this.service.deleteTransaction(id, { scope: 'SINGLE' }), 'Erro ao excluir transação.')),
        ...plan.transferIds.map(id =>
          safeDelete(this.transferService.deleteTransfer(id), 'Erro ao excluir transferência.')),
      ];

      forkJoin(deletes$).subscribe(results => {
        const okCount = results.filter(r => r.success).length;
        const failed = results.filter((r): r is { success: false; reason: string } => !r.success);
        const msg = failed.length > 0
          ? `${okCount} excluída(s). ${failed.length} falharam (${failed[0].reason}).`
          : `${okCount} excluída(s).`;
        this.snackBar.open(msg, 'OK', { duration: 5000 });
        this.clearSelection();
        this.loadTransactions();
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
          error: (err) => this.snackBar.open(
            err.error?.message ?? 'Erro ao excluir parcela.', 'Fechar', { duration: 5000 },
          ),
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
          error: (err) => this.snackBar.open(
            err.error?.message ?? 'Erro ao excluir transferência.', 'Fechar', { duration: 5000 },
          ),
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
        error: (err) => this.snackBar.open(
          err.error?.message ?? 'Erro ao excluir transação.', 'Fechar', { duration: 5000 },
        ),
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

  exportCsv(): void {
    const reais = this.filteredTransactions().filter(t => !isGhost(t));
    const csv   = exportToCsv(reais);
    triggerCsvDownload(csv, `transacoes-${new Date().toISOString().slice(0, 10)}.csv`);
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
