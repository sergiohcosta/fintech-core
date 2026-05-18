import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { TransactionService } from '../../../core/services/transaction';
import { TransactionResponse } from '../../../core/models/transaction';

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
    MatSnackBarModule,
    CurrencyPipe,
    DatePipe
  ],
  templateUrl: './transaction-list.html',
  styleUrl: './transaction-list.scss'
})
export class TransactionList implements OnInit {
  private service = inject(TransactionService);
  private snackBar = inject(MatSnackBar);

  transactions = signal<TransactionResponse[]>([]);
  displayedColumns = ['description', 'amount', 'date', 'type', 'status', 'installment', 'category', 'creditCard'];

  ngOnInit(): void {
    this.loadTransactions();
  }

  loadTransactions(): void {
    this.service.list().subscribe({
      next: (data) => this.transactions.set(data),
      error: () => this.snackBar.open('Erro ao carregar transações.', 'Fechar', { duration: 5000 })
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
