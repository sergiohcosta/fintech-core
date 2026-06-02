import { Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { switchMap } from 'rxjs';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { DashboardService } from '../../core/api/dashboard/dashboard.service';
import { TransactionsService } from '../../core/api/transactions/transactions.service';

@Component({
  selector: 'app-dashboard',
  imports: [
    RouterLink,
    CurrencyPipe,
    DatePipe,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatDividerModule,
    MatChipsModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class DashboardComponent {
  private dashboardService = inject(DashboardService);
  private transactionService = inject(TransactionsService);

  private now = new Date();
  selectedYear = signal(this.now.getFullYear());
  selectedMonthIndex = signal(this.now.getMonth() + 1); // 1-12

  // "2026-05" — formato esperado pelo backend
  selectedMonth = computed(() => {
    const m = String(this.selectedMonthIndex()).padStart(2, '0');
    return `${this.selectedYear()}-${m}`;
  });

  monthLabel = computed(() => {
    const d = new Date(this.selectedYear(), this.selectedMonthIndex() - 1, 1);
    const label = d.toLocaleDateString('pt-BR', { month: 'long', year: 'numeric' });
    return label.charAt(0).toUpperCase() + label.slice(1);
  });

  isCurrentMonth = computed(() => {
    const n = new Date();
    return this.selectedYear() === n.getFullYear() && this.selectedMonthIndex() === n.getMonth() + 1;
  });

  // toObservable + switchMap: quando selectedMonth muda, cancela a request anterior e dispara nova
  private summary$ = toObservable(this.selectedMonth).pipe(
    switchMap(month => this.dashboardService.getDashboardSummary({ month }))
  );
  summary = toSignal(this.summary$, { initialValue: null });
  hasTransactions = computed(() => (this.summary()?.transactionCount ?? 0) > 0);

  // Transações recentes: reaproveitamos o service existente, exibimos as 5 primeiras
  recentTransactions = toSignal(this.transactionService.listTransactions(), { initialValue: [] });

  prevMonth() {
    if (this.selectedMonthIndex() === 1) {
      this.selectedMonthIndex.set(12);
      this.selectedYear.update(y => y - 1);
    } else {
      this.selectedMonthIndex.update(m => m - 1);
    }
  }

  nextMonth() {
    if (this.isCurrentMonth()) return;
    if (this.selectedMonthIndex() === 12) {
      this.selectedMonthIndex.set(1);
      this.selectedYear.update(y => y + 1);
    } else {
      this.selectedMonthIndex.update(m => m + 1);
    }
  }

  typeLabel(type: string): string {
    return ({ INCOME: 'Receita', EXPENSE: 'Despesa', TRANSFER: 'Transferência' })[type] ?? type;
  }

  typeColor(type: string): string {
    return ({ INCOME: 'income', EXPENSE: 'expense', TRANSFER: 'transfer' })[type] ?? '';
  }
}
