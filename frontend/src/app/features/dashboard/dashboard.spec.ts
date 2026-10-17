import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection, signal, computed, LOCALE_ID } from '@angular/core';
import { of } from 'rxjs';
import { vi } from 'vitest';

import { DashboardComponent } from './dashboard';
import { DashboardService } from '../../core/api/dashboard/dashboard.service';
import { TransactionsService } from '../../core/api/transactions/transactions.service';
import { AccountsService } from '../../core/api/accounts/accounts.service';
import { TransactionEventsService } from '../../core/services/transaction-events';
import { AuthService } from '../../core/services/auth';
import { AccountResponse, DashboardSummaryDTO, TransactionResponseDTO } from '../../core/api/fintechSaaSAPI.schemas';
import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';

registerLocaleData(localePt, 'pt-BR');

describe('DashboardComponent', () => {
  let component: DashboardComponent;
  let fixture: ComponentFixture<DashboardComponent>;

  const mockUser = signal<{ name: string; email: string } | null>({ name: 'Sérgio Silva', email: 'sergio@test.com' });
  const mockAuthService = {
    currentUser: mockUser,
    isAdmin: signal(true),
  };

  const mockAccount: AccountResponse = {
    id: 'acc-1',
    name: 'Nubank',
    type: 'CHECKING',
    balance: 1500.5,
    active: true,
    countInLiquidBalance: true,
    countInNetWorth: true,
  };

  const mockSummary: DashboardSummaryDTO = {
    period: '2026-10',
    totalIncome: 10000,
    totalExpense: 4000,
    balance: 6000,
    transactionCount: 5,
    totalAccountBalance: 1500.5,
  };

  const mockTransaction: TransactionResponseDTO = {
    id: 'tx-1',
    description: 'Supermercado',
    amount: 250,
    type: 'EXPENSE',
    status: 'PAID',
    date: '2026-10-15',
    accountName: 'Nubank',
    categoryName: 'Alimentação',
    categoryIcon: 'shopping_cart',
  };

  beforeEach(async () => {
    localStorage.clear();

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: mockAuthService },
        { provide: LOCALE_ID, useValue: 'pt-BR' },
      ],
    }).compileComponents();

    const dashboardService = TestBed.inject(DashboardService);
    const transactionsService = TestBed.inject(TransactionsService);
    const accountsService = TestBed.inject(AccountsService);

    vi.spyOn(dashboardService, 'getDashboardSummary').mockReturnValue(of(mockSummary) as any);
    vi.spyOn(transactionsService, 'listTransactions').mockReturnValue(of([mockTransaction]) as any);
    vi.spyOn(accountsService, 'listAccounts').mockReturnValue(of([mockAccount]) as any);

    fixture = TestBed.createComponent(DashboardComponent);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create and display greeting with first name', () => {
    expect(component).toBeTruthy();
    expect(component.userName()).toBe('Sérgio');
    expect(component.greeting()).toContain('Sérgio');
  });

  it('refaz o fetch do resumo, contas e da lista após criar uma transação', async () => {
    const dashboardService = TestBed.inject(DashboardService);
    const transactionsService = TestBed.inject(TransactionsService);
    const accountsService = TestBed.inject(AccountsService);

    const summarySpy = vi.spyOn(dashboardService, 'getDashboardSummary');
    const transactionsSpy = vi.spyOn(transactionsService, 'listTransactions');
    const accountsSpy = vi.spyOn(accountsService, 'listAccounts');

    const summaryCalls = summarySpy.mock.calls.length;
    const transactionCalls = transactionsSpy.mock.calls.length;
    const accountsCalls = accountsSpy.mock.calls.length;

    TestBed.inject(TransactionEventsService).notifyTransactionCreated();
    await fixture.whenStable();

    expect(summarySpy).toHaveBeenCalledTimes(summaryCalls + 1);
    expect(transactionsSpy).toHaveBeenCalledTimes(transactionCalls + 1);
    expect(accountsSpy).toHaveBeenCalledTimes(accountsCalls + 1);
  });

  describe('Modo Privacidade', () => {
    it('alterna o modo privacidade e persiste no localStorage', () => {
      expect(component.hideValues()).toBe(false);

      component.togglePrivacy();
      expect(component.hideValues()).toBe(true);
      expect(localStorage.getItem('fintech_hide_values')).toBe('true');

      component.togglePrivacy();
      expect(component.hideValues()).toBe(false);
      expect(localStorage.getItem('fintech_hide_values')).toBe('false');
    });

    it('mascara valores monetários quando hideValues é true', () => {
      expect(component.formatMoney(1500.5)).toContain('1.500,50');
      expect(component.formatTransactionAmount(mockTransaction)).toContain('-R$');

      component.togglePrivacy();

      expect(component.formatMoney(1500.5)).toBe('R$ •••••');
      expect(component.formatTransactionAmount(mockTransaction)).toBe('R$ •••••');
    });
  });

  describe('Estados e Métricas Derivadas', () => {
    it('detecta contas ativas corretamente (hasAccounts)', () => {
      expect(component.hasAccounts()).toBe(true);
    });

    it('calcula a taxa de poupança (savingsRate) corretamente', () => {
      // 10000 receita, 4000 despesa -> saldo 6000 -> 60%
      expect(component.savingsRate()).toBe(60);
    });

    it('retorna ícones e labels correspondentes para os tipos de conta', () => {
      expect(component.getAccountIcon('CHECKING')).toBe('account_balance');
      expect(component.getAccountIcon('INVESTMENT')).toBe('trending_up');
      expect(component.getAccountIcon('CREDIT_CARD')).toBe('credit_card');
      expect(component.getAccountIcon('CASH')).toBe('payments');

      expect(component.getAccountTypeLabel('CHECKING')).toBe('Conta Corrente');
      expect(component.getAccountTypeLabel('INVESTMENT')).toBe('Investimento');
    });

    it('navega entre meses e respeita limite do mês atual', () => {
      const initialMonth = component.selectedMonthIndex();
      component.prevMonth();
      expect(component.selectedMonthIndex()).not.toBe(initialMonth);

      // nextMonth volta para o mês inicial
      component.nextMonth();
      expect(component.selectedMonthIndex()).toBe(initialMonth);

      // Avançar além do mês atual não tem efeito
      if (component.isCurrentMonth()) {
        component.nextMonth();
        expect(component.selectedMonthIndex()).toBe(initialMonth);
      }
    });
  });
});
