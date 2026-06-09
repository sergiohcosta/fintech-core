import { Component, inject, OnInit, signal, effect } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';

import { finalize } from 'rxjs/operators';

import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { InvoicesService } from '../../../core/api/invoices/invoices.service';
import { AccountResponse, InvoiceResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';
import { InvoicePayDialogComponent, InvoicePayDialogResult } from '../invoice-pay-dialog/invoice-pay-dialog';

@Component({
  selector: 'app-invoice-list',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe, RouterLink,
    MatTableModule, MatButtonModule, MatIconModule,
    MatSelectModule, MatFormFieldModule, MatSnackBarModule, MatDialogModule
  ],
  templateUrl: './invoice-list.html',
  styleUrl: './invoice-list.scss'
})
export class InvoiceList implements OnInit {
  private accountsService = inject(AccountsService);
  private invoicesService = inject(InvoicesService);
  private route = inject(ActivatedRoute);
  private snackBar = inject(MatSnackBar);
  private dialog = inject(MatDialog);

  creditCardAccounts = signal<AccountResponse[]>([]);
  selectedId = signal<string | null>(null);
  invoices = signal<InvoiceResponseDTO[]>([]);
  loading = signal(false);

  displayedColumns = ['label', 'closingDate', 'dueDate', 'transactionCount', 'totalAmount', 'status', 'actions'];

  constructor() {
    // Recarrega faturas sempre que a conta selecionada muda.
    // onCleanup cancela a subscription anterior se o usuário trocar de conta rapidamente.
    effect((onCleanup) => {
      const id = this.selectedId();
      if (!id) {
        this.invoices.set([]);
        return;
      }
      this.loading.set(true);
      const sub = this.invoicesService.listInvoices({ accountId: id })
        .pipe(finalize(() => this.loading.set(false)))
        .subscribe({
          next: (data) => this.invoices.set(data),
          error: () => this.snackBar.open('Erro ao carregar faturas.', 'Fechar', { duration: 5000 })
        });
      onCleanup(() => sub.unsubscribe());
    });
  }

  ngOnInit(): void {
    this.accountsService.listAccounts().subscribe({
      next: (data) => {
        const cc = data.filter(a => a.type === 'CREDIT_CARD');
        this.creditCardAccounts.set(cc);
        const preselect = this.route.snapshot.queryParamMap.get('accountId');
        if (preselect && cc.some(a => a.id === preselect)) {
          this.selectedId.set(preselect);
        } else if (cc.length === 1) {
          this.selectedId.set(cc[0].id);
        }
      },
      error: () => this.snackBar.open('Erro ao carregar contas.', 'Fechar', { duration: 5000 })
    });
  }

  onClose(invoice: InvoiceResponseDTO): void {
    this.loading.set(true);
    this.invoicesService.closeInvoice(invoice.id)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (updated) => {
          this.invoices.update(list => list.map(inv => inv.id === updated.id ? updated : inv));
          this.snackBar.open('Fatura fechada com sucesso.', 'Fechar', { duration: 3000 });
        },
        error: () => this.snackBar.open('Erro ao fechar fatura.', 'Fechar', { duration: 5000 })
      });
  }

  onPay(invoice: InvoiceResponseDTO): void {
    const dialogRef = this.dialog.open(InvoicePayDialogComponent, {
      data: { invoice },
      width: '480px'
    });
    dialogRef.afterClosed().subscribe((result: InvoicePayDialogResult | undefined) => {
      if (!result) return;
      this.loading.set(true);
      this.invoicesService.payInvoice(invoice.id, { sourceAccountId: result.sourceAccountId })
        .pipe(finalize(() => this.loading.set(false)))
        .subscribe({
          next: (updated) => {
            this.invoices.update(list => list.map(inv => inv.id === updated.id ? updated : inv));
            this.snackBar.open('Fatura paga com sucesso.', 'Fechar', { duration: 3000 });
          },
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
}
