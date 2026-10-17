import { Component, inject, OnInit, signal, computed, DestroyRef } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';

import { InvoicesService } from '../../../core/api/invoices/invoices.service';
import { TransactionsService } from '../../../core/api/transactions/transactions.service';
import {
  InvoiceResponseDTO, TransactionResponseDTO,
  TransactionStatus, TransactionType, InvoiceStatus
} from '../../../core/api/fintechSaaSAPI.schemas';
import { ConfirmationDialogComponent } from '../../../components/confirmation-dialog/confirmation-dialog';
import { InvoicePayDialogComponent, InvoicePayDialogResult } from '../invoice-pay-dialog/invoice-pay-dialog';
import { computeBreakdown, CategoryBreakdownRow, exportInvoiceToCsv } from './invoice-detail.utils';
import { triggerCsvDownload } from '../../../core/csv.utils';

@Component({
  selector: 'app-invoice-detail',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe, RouterLink,
    MatTableModule, MatButtonModule, MatIconModule,
    MatCardModule, MatSnackBarModule, MatDialogModule, MatTooltipModule
  ],
  templateUrl: './invoice-detail.html',
  styleUrl: './invoice-detail.scss'
})
export class InvoiceDetail implements OnInit {
  private route = inject(ActivatedRoute);
  private destroyRef = inject(DestroyRef);
  private invoicesService = inject(InvoicesService);
  private transactionsService = inject(TransactionsService);
  private snackBar = inject(MatSnackBar);
  private dialog = inject(MatDialog);

  invoice = signal<InvoiceResponseDTO | null>(null);
  transactions = signal<TransactionResponseDTO[]>([]);
  accountInvoices = signal<InvoiceResponseDTO[]>([]);

  currentIndex = computed(() => {
    const current = this.invoice();
    if (!current) return -1;
    return this.accountInvoices().findIndex(i => i.id === current.id);
  });

  prevInvoice = computed(() => {
    const idx = this.currentIndex();
    return idx > 0 ? this.accountInvoices()[idx - 1] : null;
  });

  nextInvoice = computed(() => {
    const idx = this.currentIndex();
    const list = this.accountInvoices();
    return idx >= 0 && idx < list.length - 1 ? list[idx + 1] : null;
  });

  // CANCELLED excluídas dos cálculos financeiros; aparecem na tabela como registro histórico
  activeTransactions = computed(() =>
    this.transactions().filter(t => t.status !== TransactionStatus.CANCELLED)
  );

  totalIncome = computed(() =>
    this.activeTransactions()
      .filter(t => t.type === TransactionType.INCOME)
      .reduce((sum, t) => sum + t.amount, 0)
  );

  totalExpense = computed(() =>
    this.activeTransactions()
      .filter(t => t.type === TransactionType.EXPENSE)
      .reduce((sum, t) => sum + t.amount, 0)
  );

  netBalance = computed(() => this.totalIncome() - this.totalExpense());

  breakdown = computed<CategoryBreakdownRow[]>(() =>
    computeBreakdown(this.transactions(), this.totalExpense())
  );

  showAllBreakdown = signal(false);

  visibleBreakdown = computed<CategoryBreakdownRow[]>(() =>
    this.showAllBreakdown() ? this.breakdown() : this.breakdown().slice(0, 5)
  );

  hiddenBreakdownCount = computed(() => Math.max(0, this.breakdown().length - 5));

  installmentTxs = computed(() =>
    this.transactions().filter(t => t.installmentGroupId != null)
  );

  regularTxs = computed(() =>
    this.transactions().filter(t => t.installmentGroupId == null)
  );

  installmentSubtotal = computed(() =>
    this.installmentTxs()
      .filter(t => t.status !== TransactionStatus.CANCELLED)
      .reduce((sum, t) => sum + t.amount, 0)
  );

  transactionColumns = ['description', 'amount', 'date', 'type', 'status', 'installmentLabel'];
  breakdownColumns = ['categoryName', 'count', 'total', 'percentage'];

  // Exposto ao template para comparação de status sem string literal
  readonly InvoiceStatus = InvoiceStatus;

  ngOnInit(): void {
    if (this.route.paramMap) {
      this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(params => {
        const id = params.get('id');
        if (id) this.loadInvoice(id);
      });
    } else if (this.route.snapshot?.paramMap) {
      const id = this.route.snapshot.paramMap.get('id');
      if (id) this.loadInvoice(id);
    }
  }

  private loadInvoice(id: string): void {
    this.invoicesService.getInvoice(id).subscribe({
      next: (inv) => {
        this.invoice.set(inv);
        this.loadAccountInvoices(inv.accountId);
      },
      error: () => this.snackBar.open('Fatura não encontrada.', 'Fechar', { duration: 5000 })
    });
    this.transactionsService.listTransactions({ invoiceId: id }).subscribe({
      next: (txs) => this.transactions.set(txs),
      error: () => this.snackBar.open('Erro ao carregar transações.', 'Fechar', { duration: 5000 })
    });
  }

  private loadAccountInvoices(accountId: string): void {
    this.invoicesService.listInvoices({ accountId }).subscribe({
      next: (list) => {
        const sorted = [...list].sort((a, b) =>
          (a.referenceYear * 12 + a.referenceMonth) - (b.referenceYear * 12 + b.referenceMonth)
        );
        this.accountInvoices.set(sorted);
      },
      error: () => {}
    });
  }

  onClose(): void {
    const ref = this.dialog.open(ConfirmationDialogComponent, {
      data: { title: 'Fechar Fatura', message: 'Confirma o fechamento desta fatura? Não será mais possível adicionar transações.', confirmText: 'Fechar Fatura' }
    });
    ref.afterClosed().subscribe(confirmed => {
      if (!confirmed || !this.invoice()) return;
      this.invoicesService.closeInvoice(this.invoice()!.id).subscribe({
        next: (inv) => { this.invoice.set(inv); this.updateAccountInvoice(inv); this.snackBar.open('Fatura fechada.', 'OK', { duration: 3000 }); },
        error: () => this.snackBar.open('Erro ao fechar fatura.', 'Fechar', { duration: 5000 })
      });
    });
  }

  onPay(): void {
    const invoice = this.invoice();
    if (!invoice) return;
    const ref = this.dialog.open(InvoicePayDialogComponent, {
      data: { invoice },
      width: '480px'
    });
    ref.afterClosed().subscribe((result: InvoicePayDialogResult | undefined) => {
      if (!result) return;
      this.invoicesService.payInvoice(invoice.id, {
        sourceAccountId: result.sourceAccountId,
        paymentDate: result.paymentDate
      }).subscribe({
        next: (inv) => { this.invoice.set(inv); this.updateAccountInvoice(inv); this.snackBar.open('Fatura paga.', 'OK', { duration: 3000 }); },
        error: () => this.snackBar.open('Erro ao pagar fatura.', 'Fechar', { duration: 5000 })
      });
    });
  }

  private updateAccountInvoice(updated: InvoiceResponseDTO): void {
    this.accountInvoices.set(this.accountInvoices().map(i => i.id === updated.id ? updated : i));
  }

  exportCsv(): void {
    const inv = this.invoice();
    if (!inv) return;
    const csv = exportInvoiceToCsv([...this.installmentTxs(), ...this.regularTxs()]);
    const mes = String(inv.referenceMonth).padStart(2, '0');
    triggerCsvDownload(csv, `fatura-${inv.accountName.replace(/\s+/g, '-')}-${inv.referenceYear}-${mes}.csv`);
  }

  statusChipClass(status: string): string {
    const map: Record<string, string> = {
      OPEN:   'status-chip status-open',
      CLOSED: 'status-chip status-closed',
      PAID:   'status-chip status-paid'
    };
    return map[status] ?? 'status-chip';
  }

  statusLabel(status: string): string {
    const map: Record<string, string> = { OPEN: 'Aberta', CLOSED: 'Fechada', PAID: 'Paga' };
    return map[status] ?? status;
  }

  typeLabel(type: string): string {
    const map: Record<string, string> = { INCOME: 'Receita', EXPENSE: 'Despesa' };
    return map[type] ?? type;
  }

  transactionStatusLabel(status: string): string {
    const map: Record<string, string> = { PENDING: 'Pendente', PAID: 'Pago', CANCELLED: 'Cancelado' };
    return map[status] ?? status;
  }
}
