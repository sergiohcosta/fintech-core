import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';

import { InvoicesService } from '../../../core/api/invoices/invoices.service';
import { TransactionsService } from '../../../core/api/transactions/transactions.service';
import {
  InvoiceResponseDTO, TransactionResponseDTO,
  TransactionStatus, TransactionType, InvoiceStatus
} from '../../../core/api/fintechSaaSAPI.schemas';
import { ConfirmationDialogComponent } from '../../../components/confirmation-dialog/confirmation-dialog';
import { computeBreakdown, CategoryBreakdownRow } from './invoice-detail.utils';

@Component({
  selector: 'app-invoice-detail',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe,
    MatTableModule, MatButtonModule, MatIconModule,
    MatCardModule, MatSnackBarModule, MatDialogModule
  ],
  templateUrl: './invoice-detail.html',
  styleUrl: './invoice-detail.scss'
})
export class InvoiceDetail implements OnInit {
  private route = inject(ActivatedRoute);
  private invoicesService = inject(InvoicesService);
  private transactionsService = inject(TransactionsService);
  private snackBar = inject(MatSnackBar);
  private dialog = inject(MatDialog);

  invoice = signal<InvoiceResponseDTO | null>(null);
  transactions = signal<TransactionResponseDTO[]>([]);

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

  transactionColumns = ['description', 'amount', 'date', 'type', 'status', 'installmentLabel'];
  breakdownColumns = ['categoryName', 'count', 'total', 'percentage'];

  // Exposto ao template para comparação de status sem string literal
  readonly InvoiceStatus = InvoiceStatus;

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id')!;
    this.invoicesService.getInvoice(id).subscribe({
      next: (inv) => this.invoice.set(inv),
      error: () => this.snackBar.open('Fatura não encontrada.', 'Fechar', { duration: 5000 })
    });
    this.transactionsService.listTransactions({ invoiceId: id }).subscribe({
      next: (txs) => this.transactions.set(txs),
      error: () => this.snackBar.open('Erro ao carregar transações.', 'Fechar', { duration: 5000 })
    });
  }

  onClose(): void {
    const ref = this.dialog.open(ConfirmationDialogComponent, {
      data: { title: 'Fechar Fatura', message: 'Confirma o fechamento desta fatura? Não será mais possível adicionar transações.', confirmText: 'Fechar Fatura' }
    });
    ref.afterClosed().subscribe(confirmed => {
      if (!confirmed) return;
      this.invoicesService.closeInvoice(this.invoice()!.id).subscribe({
        next: (inv) => { this.invoice.set(inv); this.snackBar.open('Fatura fechada.', 'OK', { duration: 3000 }); },
        error: () => this.snackBar.open('Erro ao fechar fatura.', 'Fechar', { duration: 5000 })
      });
    });
  }

  onPay(): void {
    const ref = this.dialog.open(ConfirmationDialogComponent, {
      data: { title: 'Pagar Fatura', message: 'Confirma o pagamento desta fatura?', confirmText: 'Pagar Fatura' }
    });
    ref.afterClosed().subscribe(confirmed => {
      if (!confirmed) return;
      this.invoicesService.payInvoice(this.invoice()!.id).subscribe({
        next: (inv) => { this.invoice.set(inv); this.snackBar.open('Fatura paga.', 'OK', { duration: 3000 }); },
        error: () => this.snackBar.open('Erro ao pagar fatura.', 'Fechar', { duration: 5000 })
      });
    });
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
