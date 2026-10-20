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
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatListModule } from '@angular/material/list';

import { DashboardService } from '../../core/api/dashboard/dashboard.service';
import { TransactionsService } from '../../core/api/transactions/transactions.service';
import { AccountsService } from '../../core/api/accounts/accounts.service';
import { TransactionEventsService } from '../../core/services/transaction-events';
import { AuthService } from '../../core/services/auth';
import { PlanningService } from '../planning/planning.service';
import { AccountResponse, BudgetCycleResponse, BudgetCycleSummary, TransactionResponseDTO } from '../../core/api/fintechSaaSAPI.schemas';
import { DEFAULT_SUMMARY } from '../planning/budget-cycle-current/budget-cycle.utils';

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
    MatProgressBarModule,
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
  private planningService = inject(PlanningService);

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
  accounts = toSignal(this.accounts$);
  hasAccounts = computed(() => (this.accounts()?.length ?? 0) > 0);

  // Carregamento do Planejamento Ativo (Budget Cycle)
  private currentCycle$ = toObservable(this.transactionEvents.refreshTrigger).pipe(
    switchMap(() => this.planningService.getCurrentCycle().pipe(catchError(() => of(null))))
  );
  currentCycle = toSignal(this.currentCycle$);
  hasActiveCycle = computed(() => {
    const c = this.currentCycle();
    return !!c && c.status === 'OPEN';
  });
  cycleSummary = computed((): Required<BudgetCycleSummary> => ({
    ...DEFAULT_SUMMARY,
    ...(this.currentCycle()?.summary ?? {}),
  }));

  // Carregamento Inicial: evita flash do onboarding e de ciclo vazio pós-login
  isLoading = computed(() => {
    const accs = this.accounts();
    if (accs === undefined) return true;
    if (accs.length === 0) return false;
    return this.currentCycle() === undefined;
  });

  cycleDatesLabel = computed(() => {
    const c = this.currentCycle();
    if (!c || !c.startDate || !c.endDate) return '';
    const [sy, sm, sd] = c.startDate.split('-').map(Number);
    const [ey, em, ed] = c.endDate.split('-').map(Number);
    const start = new Date(sy, sm - 1, sd);
    const end = new Date(ey, em - 1, ed);
    const fmt = (d: Date) => d.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' });
    return `${fmt(start)} a ${fmt(end)}/${end.getFullYear()}`;
  });

  availableToSpend = computed(() => this.cycleSummary().availableToSpend);
  dailyAllowance = computed(() => this.cycleSummary().dailyAllowance);
  remainingDays = computed(() => this.cycleSummary().remainingDays);

  expenseProgress = computed(() => {
    const planned = this.cycleSummary().plannedExpense;
    const realized = this.cycleSummary().realizedExpense;
    if (!planned || planned <= 0) return 0;
    return Math.min(100, Math.round((realized / planned) * 100));
  });

  incomeProgress = computed(() => {
    const planned = this.cycleSummary().plannedIncome;
    const realized = this.cycleSummary().realizedIncome;
    if (!planned || planned <= 0) return 0;
    return Math.min(100, Math.round((realized / planned) * 100));
  });

  liquidBalance = computed(() => {
    const s = this.summary();
    if (s?.totalAccountBalance !== undefined && s?.totalAccountBalance !== null) {
      return s.totalAccountBalance;
    }
    const accs = this.accounts();
    if (!accs || accs.length === 0) return 0;
    return accs
      .filter(a => a.active && a.countInLiquidBalance)
      .reduce((sum, a) => sum + (a.balance ?? 0), 0);
  });

  // Carregamento do Resumo Financeiro
  private refreshState = computed(() => ({
    refresh: this.transactionEvents.refreshTrigger(),
    month: this.selectedMonth(),
  }));
  private summary$ = toObservable(this.refreshState).pipe(
    switchMap(({ month }) => this.dashboardService.getDashboardSummary({ month }).pipe(catchError(() => of(null))))
  );
  summary = toSignal(this.summary$, { initialValue: null });

  // Transações recentes: exibimos as 5 primeiras
  private recentTransactions$ = toObservable(this.transactionEvents.refreshTrigger).pipe(
    switchMap(() => this.transactionService.listTransactions().pipe(catchError(() => of([]))))
  );
  recentTransactions = toSignal(this.recentTransactions$, { initialValue: [] as TransactionResponseDTO[] });

  hasTransactions = computed(() => (this.summary()?.transactionCount ?? 0) > 0 || (this.recentTransactions()?.length ?? 0) > 0);

  // Taxa de Poupança / Economia do mês (%)
  savingsRate = computed<number | null>(() => {
    const s = this.summary();
    if (!s || !s.totalIncome || s.totalIncome <= 0) return null;
    const balance = s.balance ?? (s.totalIncome - (s.totalExpense ?? 0));
    return Math.round((balance / s.totalIncome) * 100);
  });

  // Checklist de Onboarding Progressivo (Primeiros Passos)
  private getInitialOnboardingDismissedState(): boolean {
    try {
      return localStorage.getItem('fintech_onboarding_dismissed') === 'true';
    } catch {
      return false;
    }
  }

  onboardingDismissed = signal<boolean>(this.getInitialOnboardingDismissedState());

  dismissOnboarding(): void {
    this.onboardingDismissed.set(true);
    try {
      localStorage.setItem('fintech_onboarding_dismissed', 'true');
    } catch {}
  }

  onboardingSteps = computed(() => [
    {
      id: 'account',
      title: 'Cadastrar primeira conta ou cartão',
      description: 'Conta bancária, carteira ou cartão de crédito para gerenciar seu saldo.',
      completed: this.hasAccounts(),
      actionUrl: '/accounts/new',
      actionLabel: 'Criar Conta',
      actionIcon: 'add',
      secondaryActionUrl: null,
      secondaryActionLabel: null,
      secondaryActionIcon: null,
    },
    {
      id: 'transaction',
      title: 'Lançar ou importar primeira movimentação',
      description: 'Importe seu extrato bancário ou registre uma despesa do dia a dia.',
      completed: this.hasTransactions(),
      actionUrl: '/transactions/new',
      actionLabel: 'Lançar Despesa',
      actionIcon: 'add',
      secondaryActionUrl: '/import',
      secondaryActionLabel: 'Importar Extrato',
      secondaryActionIcon: 'upload_file',
    },
    {
      id: 'planning',
      title: 'Abrir planejamento do mês',
      description: 'Defina o período do seu orçamento e acompanhe seu limite diário para gastar.',
      completed: this.hasActiveCycle(),
      actionUrl: '/planning',
      actionLabel: 'Abrir Planejamento',
      actionIcon: 'event_note',
      secondaryActionUrl: null,
      secondaryActionLabel: null,
      secondaryActionIcon: null,
    },
  ]);

  onboardingCompletedCount = computed(() => {
    return this.onboardingSteps().filter(s => s.completed).length;
  });

  onboardingTotalCount = computed(() => this.onboardingSteps().length);

  isOnboardingComplete = computed(() => {
    return this.onboardingCompletedCount() === this.onboardingTotalCount();
  });

  showOnboardingChecklist = computed(() => {
    return this.hasAccounts() && !this.isOnboardingComplete() && !this.onboardingDismissed();
  });

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
