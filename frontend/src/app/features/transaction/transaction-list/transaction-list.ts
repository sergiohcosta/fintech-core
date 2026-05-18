import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { TransactionService } from '../../../core/services/transaction';
import { TransactionResponse } from '../../../core/models/transaction';
import { ConfirmationDialogComponent } from '../../../components/confirmation-dialog/confirmation-dialog';

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
    CurrencyPipe,
    DatePipe
  ],
  templateUrl: './transaction-list.html',
  styleUrl: './transaction-list.scss'
})
export class TransactionList implements OnInit {
  private service = inject(TransactionService);
  private router = inject(Router);
  private dialog = inject(MatDialog);
  private snackBar = inject(MatSnackBar);

  transactions = signal<TransactionResponse[]>([]);
  displayedColumns = ['description', 'amount', 'date', 'type', 'status', 'installment', 'category', 'creditCard', 'actions'];

  ngOnInit(): void {
    this.loadTransactions();
  }

  loadTransactions(): void {
    this.service.list().subscribe({
      next: (data) => this.transactions.set(data),
      error: () => this.snackBar.open('Erro ao carregar transações.', 'Fechar', { duration: 5000 })
    });
  }

  onEdit(t: TransactionResponse): void {
    this.router.navigate(['/transactions', t.id]);
  }

  onDelete(t: TransactionResponse): void {
    const dialogRef = this.dialog.open(ConfirmationDialogComponent, {
      width: '400px',
      data: {
        title: 'Excluir Transação',
        message: `Deseja excluir "${t.description}"? Esta ação não pode ser desfeita.`,
        confirmText: 'Sim, excluir'
      }
    });

    dialogRef.afterClosed().subscribe(confirmed => {
      if (confirmed === true) {
        this.service.delete(t.id).subscribe({
          next: () => {
            this.snackBar.open('Transação excluída.', 'OK', { duration: 3000 });
            this.loadTransactions();
          },
          error: () => this.snackBar.open('Erro ao excluir transação.', 'Fechar', { duration: 5000 })
        });
      }
    });
  }

  typeLabel(type: string): string {
    const labels: Record<string, string> = {
      INCOME: 'Receita',
      EXPENSE: 'Despesa',
      TRANSFER: 'Transferência'
    };
    return labels[type] ?? type;
  }

  statusLabel(status: string): string {
    const labels: Record<string, string> = {
      PENDING: 'Pendente',
      PAID: 'Pago',
      CANCELLED: 'Cancelado'
    };
    return labels[status] ?? status;
  }
}
