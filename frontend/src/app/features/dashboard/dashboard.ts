import { Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { switchMap, catchError, of } from 'rxjs';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatListModule } from '@angular/material/list';

import { DashboardService } from '../../core/api/dashboard/dashboard.service';
import { TransactionsService } from '../../core/api/transactions/transactions.service';
import { AccountsService } from '../../core/api/accounts/accounts.service';
import { TransactionEventsService } from '../../core/services/transaction-events';
import { AuthService } from '../../core/services/auth';
import { AccountResponse, TransactionResponseDTO } from '../../core/api/fintechSaaSAPI.schemas';

@Component({
  selector: 'app-dashboard',
  imports: [
    RouterLink,
    DatePipe,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatDividerModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatListModule,
  ],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class DashboardComponent {
  private dashboardService = inject(DashboardService);
  private transactionService = inject(TransactionsService);
  private accountsService = inject(AccountsService);
  private transactionEvents = inject(TransactionEventsService);
  private authService = inject(AuthService);

  private now = new Date();
  selectedYear = signal(this.now.getFullYear());
  selectedMonthIndex = signal(this.now.getMonth() + 1); // 1-12

  // Modo Privacidade
  private getInitialPrivacyState(): boolean {
    try {
      return localStorage.getItem('fintech_hide_values') === 'true';
    } catch {
      return false;
    }
  }

  hideValues = signal<boolean>(this.getInitialPrivacyState());

  togglePrivacy(): void {
    this.hideValues.update(v => {
      const next = !v;
      try {
        localStorage.setItem('fintech_hide_values', String(next));
      } catch {}
      return next;
    });
  }

  // Saudação acolhedora
  userName = computed(() => {
    const name = this.authService.currentUser()?.name;
    if (!name) return 'você';
    return name.trim().split(' ')[0];
  });

  greeting = computed(() => {
    const hour = new Date().getHours();
    let timeGreeting = 'Olá';
    if (hour >= 5 && hour < 12) timeGreeting = 'Bom dia';
    else if (hour >= 12 && hour < 18) timeGreeting = 'Boa tarde';
    else timeGreeting = 'Boa noite';
    return `${timeGreeting}, ${this.userName()}! 👋`;
  });

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

  // Carregamento de Contas
  private accounts$ = toObservable(this.transactionEvents.refreshTrigger).pipe(
    switchMap(() => this.accountsService.listAccounts().pipe(catchError(() => of([]))))
  );
  accounts = toSignal(this.accounts$, { initialValue: [] as AccountResponse[] });
  hasAccounts = computed(() => (this.accounts()?.length ?? 0) > 0);

  // Carregamento do Resumo Financeiro
  private refreshState = computed(() => ({
    refresh: this.transactionEvents.refreshTrigger(),
    month: this.selectedMonth(),
  }));
  private summary$ = toObservable(this.refreshState).pipe(
    switchMap(({ month }) => this.dashboardService.getDashboardSummary({ month }).pipe(catchError(() => of(null))))
  );
  summary = toSignal(this.summary$, { initialValue: null });
  hasTransactions = computed(() => (this.summary()?.transactionCount ?? 0) > 0);

  // Taxa de Poupança / Economia do mês (%)
  savingsRate = computed<number | null>(() => {
    const s = this.summary();
    if (!s || !s.totalIncome || s.totalIncome <= 0) return null;
    const balance = s.balance ?? (s.totalIncome - (s.totalExpense ?? 0));
    return Math.round((balance / s.totalIncome) * 100);
  });

  // Transações recentes: exibimos as 5 primeiras
  private recentTransactions$ = toObservable(this.transactionEvents.refreshTrigger).pipe(
    switchMap(() => this.transactionService.listTransactions().pipe(catchError(() => of([]))))
  );
  recentTransactions = toSignal(this.recentTransactions$, { initialValue: [] as TransactionResponseDTO[] });

  prevMonth(): void {
    if (this.selectedMonthIndex() === 1) {
      this.selectedMonthIndex.set(12);
      this.selectedYear.update(y => y - 1);
    } else {
      this.selectedMonthIndex.update(m => m - 1);
    }
  }

  nextMonth(): void {
    if (this.isCurrentMonth()) return;
    if (this.selectedMonthIndex() === 12) {
      this.selectedMonthIndex.set(1);
      this.selectedYear.update(y => y + 1);
    } else {
      this.selectedMonthIndex.update(m => m + 1);
    }
  }

  formatMoney(amount: number | null | undefined): string {
    if (this.hideValues()) {
      return 'R$ •••••';
    }
    const val = amount ?? 0;
    return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(val);
  }

  formatTransactionAmount(t: TransactionResponseDTO): string {
    if (this.hideValues()) {
      return 'R$ •••••';
    }
    const prefix = t.type === 'EXPENSE' ? '-' : '+';
    const val = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(Math.abs(t.amount ?? 0));
    return `${prefix}${val}`;
  }

  getAccountIcon(type: string | undefined): string {
    switch (type) {
      case 'CHECKING': return 'account_balance';
      case 'INVESTMENT': return 'trending_up';
      case 'CREDIT_CARD': return 'credit_card';
      case 'CASH': return 'payments';
      default: return 'account_balance_wallet';
    }
  }

  getAccountTypeLabel(type: string | undefined): string {
    switch (type) {
      case 'CHECKING': return 'Conta Corrente';
      case 'INVESTMENT': return 'Investimento';
      case 'CREDIT_CARD': return 'Cartão de Crédito';
      case 'CASH': return 'Dinheiro';
      default: return 'Conta';
    }
  }

  getTransactionIcon(t: TransactionResponseDTO): string {
    if (t.categoryIcon) return t.categoryIcon;
    if (t.transferId) return 'sync_alt';
    switch (t.type) {
      case 'INCOME': return 'arrow_downward';
      case 'EXPENSE': return 'arrow_upward';
      default: return 'receipt';
    }
  }

  typeLabel(type: string): string {
    return ({ INCOME: 'Receita', EXPENSE: 'Despesa', TRANSFER: 'Transferência' })[type] ?? type;
  }

  typeColor(type: string): string {
    return ({ INCOME: 'income', EXPENSE: 'expense', TRANSFER: 'transfer' })[type] ?? '';
  }
}
