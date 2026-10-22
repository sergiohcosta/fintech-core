import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection, signal, LOCALE_ID } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { describe, it, expect, beforeEach, vi } from 'vitest';

import { DashboardComponent } from './dashboard';
import { DashboardService } from '../../core/api/dashboard/dashboard.service';
import { TransactionsService } from '../../core/api/transactions/transactions.service';
import { AccountsService } from '../../core/api/accounts/accounts.service';
import { InvoicesService } from '../../core/api/invoices/invoices.service';
import { PlanningService } from '../planning/planning.service';
import { TransactionEventsService } from '../../core/services/transaction-events';
import { AuthService } from '../../core/services/auth';
import { AccountResponse, BudgetCycleResponse, DashboardSummaryDTO, InvoiceResponseDTO, TransactionResponseDTO } from '../../core/api/fintechSaaSAPI.schemas';
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

  const mockCycle: BudgetCycleResponse = {
    id: 'cycle-1',
    startDate: '2026-10-05',
    endDate: '2026-11-04',
    status: 'OPEN',
    openingBalance: 1000,
    summary: {
      plannedIncome: 8000,
      realizedIncome: 8000,
      plannedExpense: 5000,
      realizedExpense: 3200,
      currentBalance: 5800,
      projectedBalance: 4000,
      availableToSpend: 1800,
      dailyAllowance: 112.5,
      remainingDays: 16,
      pendingCount: 2,
      unplannedIncome: 0,
      unplannedExpense: 200,
    },
  };

  beforeEach(async () => {
    localStorage.clear();

    TestBed.resetTestingModule();
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
    const invoicesService = TestBed.inject(InvoicesService);
    const planningService = TestBed.inject(PlanningService);

    vi.spyOn(dashboardService, 'getDashboardSummary').mockReturnValue(of(mockSummary) as any);
    vi.spyOn(transactionsService, 'listTransactions').mockReturnValue(of([mockTransaction]) as any);
    vi.spyOn(accountsService, 'listAccounts').mockReturnValue(of([mockAccount]) as any);
    vi.spyOn(invoicesService, 'listInvoices').mockReturnValue(of([]) as any);
    vi.spyOn(planningService, 'getCurrentCycle').mockReturnValue(of(mockCycle) as any);

    fixture = TestBed.createComponent(DashboardComponent);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create and display greeting with first name', () => {
    expect(component).toBeTruthy();
    expect(component.userName()).toBe('Sérgio');
    expect(component.greeting()).toContain('Sérgio');
  });

  it('refaz o fetch do resumo, contas, ciclo e lista após criar uma transação', async () => {
    const dashboardService = TestBed.inject(DashboardService);
    const transactionsService = TestBed.inject(TransactionsService);
    const accountsService = TestBed.inject(AccountsService);
    const planningService = TestBed.inject(PlanningService);

    const summarySpy = vi.spyOn(dashboardService, 'getDashboardSummary');
    const transactionsSpy = vi.spyOn(transactionsService, 'listTransactions');
    const accountsSpy = vi.spyOn(accountsService, 'listAccounts');
    const cycleSpy = vi.spyOn(planningService, 'getCurrentCycle');

    const summaryCalls = summarySpy.mock.calls.length;
    const transactionCalls = transactionsSpy.mock.calls.length;
    const accountsCalls = accountsSpy.mock.calls.length;
    const cycleCalls = cycleSpy.mock.calls.length;

    TestBed.inject(TransactionEventsService).notifyTransactionCreated();
    await fixture.whenStable();

    expect(summarySpy).toHaveBeenCalledTimes(summaryCalls + 1);
    expect(transactionsSpy).toHaveBeenCalledTimes(transactionCalls + 1);
    expect(accountsSpy).toHaveBeenCalledTimes(accountsCalls + 1);
    expect(cycleSpy).toHaveBeenCalledTimes(cycleCalls + 1);
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

  describe('Planejamento Ativo (Budget Cycle)', () => {
    it('reconhece o ciclo aberto e calcula os KPIs corretamente', () => {
      expect(component.hasActiveCycle()).toBe(true);
      expect(component.availableToSpend()).toBe(1800);
      expect(component.dailyAllowance()).toBe(112.5);
      expect(component.remainingDays()).toBe(16);
      expect(component.cycleDatesLabel()).toContain('2026');
      expect(component.expenseProgress()).toBe(64); // 3200 / 5000 = 64%
    });

    it('lida graciosamente com ausência de ciclo ativo', async () => {
      const planningService = TestBed.inject(PlanningService);
      vi.spyOn(planningService, 'getCurrentCycle').mockReturnValue(throwError(() => ({ status: 404 })));

      TestBed.inject(TransactionEventsService).notifyTransactionCreated();
      await fixture.whenStable();

      expect(component.hasActiveCycle()).toBe(false);
      expect(component.availableToSpend()).toBe(0);
      expect(component.dailyAllowance()).toBeNull();
    });
  });

  describe('Estados e Métricas Derivadas', () => {
    it('detecta contas ativas e calcula a posição líquida (liquidBalance)', () => {
      expect(component.hasAccounts()).toBe(true);
      expect(component.liquidBalance()).toBe(1500.5);
    });

    it('calcula a taxa de poupança (savingsRate) corretamente', () => {
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

      component.nextMonth();
      expect(component.selectedMonthIndex()).toBe(initialMonth);

      if (component.isCurrentMonth()) {
        component.nextMonth();
        expect(component.selectedMonthIndex()).toBe(initialMonth);
      }
    });
  });

  describe('Carregamento Inicial e Prevenção de Flicker no Onboarding', () => {
    it('encerra o estado de carregamento (isLoading=false) quando dados essenciais estão carregados', () => {
      expect(component.isLoading()).toBe(false);
      expect(component.hasAccounts()).toBe(true);
    });

    it('libera o onboarding imediatamente quando a lista de contas retorna vazia (tenant recém-criado)', async () => {
      const accountsService = TestBed.inject(AccountsService);
      vi.spyOn(accountsService, 'listAccounts').mockReturnValue(of([]) as any);

      TestBed.inject(TransactionEventsService).notifyTransactionCreated();
      await fixture.whenStable();

      expect(component.isLoading()).toBe(false);
      expect(component.hasAccounts()).toBe(false);
    });
  });

  describe('Checklist Progressivo de Onboarding (Primeiros Passos)', () => {
    it('calcula o progresso dos passos baseado no estado da aplicação', () => {
      // No setup padrão: tem conta (acc-1), tem transação (tx-1), tem ciclo aberto (cycle-1)
      expect(component.onboardingTotalCount()).toBe(3);
      expect(component.onboardingCompletedCount()).toBe(3);
      expect(component.isOnboardingComplete()).toBe(true);
      expect(component.showOnboardingChecklist()).toBe(false);
    });

    it('exibe o checklist quando há contas mas faltam transações ou planejamento', async () => {
      const planningService = TestBed.inject(PlanningService);
      vi.spyOn(planningService, 'getCurrentCycle').mockReturnValue(of(null) as any);

      TestBed.inject(TransactionEventsService).notifyTransactionCreated();
      await fixture.whenStable();

      expect(component.hasActiveCycle()).toBe(false);
      expect(component.isOnboardingComplete()).toBe(false);
      expect(component.showOnboardingChecklist()).toBe(true);
    });

    it('dispensa o checklist quando dismissOnboarding é chamado e persiste no localStorage', () => {
      expect(component.onboardingDismissed()).toBe(false);

      component.dismissOnboarding();

      expect(component.onboardingDismissed()).toBe(true);
      expect(localStorage.getItem('fintech_onboarding_dismissed')).toBe('true');
      expect(component.showOnboardingChecklist()).toBe(false);
    });
  });

  describe('Radar de Faturas e Cartões (Sinopse por Cartão)', () => {
    it('carrega e gera sinopse detalhada por cartão com fatura aberta e limites', async () => {
      const mockCreditCard: AccountResponse = {
        id: 'cc-1',
        name: 'Nubank Ultravioleta',
        type: 'CREDIT_CARD',
        balance: 450,
        active: true,
        countInLiquidBalance: false,
        countInNetWorth: true,
        creditCardDetails: {
          limitAmount: 5000,
          closingDay: 25,
          dueDay: 2,
          lastFourDigits: '9876',
        },
      };

      const mockInvoice: InvoiceResponseDTO = {
        id: 'inv-1',
        accountId: 'cc-1',
        accountName: 'Nubank Ultravioleta',
        referenceMonth: 10,
        referenceYear: 2026,
        label: 'Outubro/2026',
        closingDate: '2026-10-25',
        dueDate: '2026-11-02',
        status: 'OPEN',
        totalAmount: 850.75,
        transactionCount: 8,
      };

      const accountsService = TestBed.inject(AccountsService);
      const invoicesService = TestBed.inject(InvoicesService);

      vi.spyOn(accountsService, 'listAccounts').mockReturnValue(of([mockAccount, mockCreditCard]) as any);
      vi.spyOn(invoicesService, 'listInvoices').mockReturnValue(of([mockInvoice]) as any);

      TestBed.inject(TransactionEventsService).notifyTransactionCreated();
      await fixture.whenStable();

      expect(component.creditCardAccounts().length).toBe(1);
      expect(component.creditCardInvoices().length).toBe(1);
      expect(component.creditCardInvoices()[0].id).toBe('inv-1');
      expect(component.creditCardInvoices()[0].totalAmount).toBe(850.75);

      // Verificação da Sinopse por Cartão
      const synopses = component.cardSynopses();
      expect(synopses.length).toBe(1);
      const synopsis = synopses[0];
      expect(synopsis.account.id).toBe('cc-1');
      expect(synopsis.featuredInvoice?.id).toBe('inv-1');
      expect(synopsis.isOpenInvoice).toBe(true);
      expect(synopsis.isClosedWaitingPayment).toBe(false);
      expect(synopsis.limitAmount).toBe(5000);
      expect(synopsis.usedBalance).toBe(450);
      expect(synopsis.availableLimit).toBe(4550);
      expect(synopsis.limitUsagePercentage).toBe(9); // 450 / 5000 = 9%
      expect(synopsis.closingDay).toBe(25);
      expect(synopsis.dueDay).toBe(2);
    });

    it('prioriza fatura FECHADA aguardando pagamento e expõe próxima fatura aberta como preview', async () => {
      const mockCreditCard: AccountResponse = {
        id: 'cc-2',
        name: 'XP Visa Infinite',
        type: 'CREDIT_CARD',
        balance: 3200,
        active: true,
        countInLiquidBalance: false,
        countInNetWorth: true,
        creditCardDetails: {
          limitAmount: 10000,
          closingDay: 15,
          dueDay: 22,
        },
      };

      const closedInvoice: InvoiceResponseDTO = {
        id: 'inv-closed',
        accountId: 'cc-2',
        accountName: 'XP Visa Infinite',
        referenceMonth: 9,
        referenceYear: 2026,
        label: 'Setembro/2026',
        closingDate: '2026-09-15',
        dueDate: '2026-09-22',
        status: 'CLOSED',
        totalAmount: 2100,
        transactionCount: 12,
      };

      const openInvoice: InvoiceResponseDTO = {
        id: 'inv-open',
        accountId: 'cc-2',
        accountName: 'XP Visa Infinite',
        referenceMonth: 10,
        referenceYear: 2026,
        label: 'Outubro/2026',
        closingDate: '2026-10-15',
        dueDate: '2026-10-22',
        status: 'OPEN',
        totalAmount: 1100,
        transactionCount: 5,
      };

      const accountsService = TestBed.inject(AccountsService);
      const invoicesService = TestBed.inject(InvoicesService);

      vi.spyOn(accountsService, 'listAccounts').mockReturnValue(of([mockCreditCard]) as any);
      vi.spyOn(invoicesService, 'listInvoices').mockReturnValue(of([closedInvoice, openInvoice]) as any);

      TestBed.inject(TransactionEventsService).notifyTransactionCreated();
      await fixture.whenStable();

      const synopses = component.cardSynopses();
      expect(synopses.length).toBe(1);
      const card = synopses[0];
      expect(card.featuredInvoice?.id).toBe('inv-closed');
      expect(card.isClosedWaitingPayment).toBe(true);
      expect(card.nextOpenInvoice?.id).toBe('inv-open');
      expect(card.availableLimit).toBe(6800); // 10000 - 3200
    });

    it('onPayInvoice abre o diálogo e efetua o pagamento da fatura com notificação', async () => {
      const invoicesService = TestBed.inject(InvoicesService);
      const eventService = TestBed.inject(TransactionEventsService);

      const notifySpy = vi.spyOn(eventService, 'notifyTransactionCreated');
      const paySpy = vi.spyOn(invoicesService, 'payInvoice').mockReturnValue(of({ id: 'inv-closed', status: 'PAID' } as any));
      const dialogRefSpy = {
        afterClosed: vi.fn().mockReturnValue(of({ sourceAccountId: 'acc-1', paymentDate: '2026-09-22' })),
      };
      const dialogSpy = vi.spyOn((component as any).dialog, 'open').mockReturnValue(dialogRefSpy as any);

      const targetInvoice: InvoiceResponseDTO = {
        id: 'inv-closed',
        accountId: 'cc-2',
        accountName: 'XP Visa Infinite',
        referenceMonth: 9,
        referenceYear: 2026,
        label: 'Setembro/2026',
        closingDate: '2026-09-15',
        dueDate: '2026-09-22',
        status: 'CLOSED',
        totalAmount: 2100,
        transactionCount: 12,
      };

      component.onPayInvoice(targetInvoice);

      expect(dialogSpy).toHaveBeenCalled();
      expect(paySpy).toHaveBeenCalledWith('inv-closed', {
        sourceAccountId: 'acc-1',
        paymentDate: '2026-09-22',
      });
      expect(notifySpy).toHaveBeenCalled();
    });
  });

  describe('Wizard de Boas-Vindas (Dia Zero)', () => {
    it('exibe o wizard quando não há contas cadastradas e o guia não foi dispensado', async () => {
      const accountsService = TestBed.inject(AccountsService);
      vi.spyOn(accountsService, 'listAccounts').mockReturnValue(of([]) as any);

      TestBed.inject(TransactionEventsService).notifyTransactionCreated();
      await fixture.whenStable();

      expect(component.hasAccounts()).toBe(false);
      expect(component.showWelcomeWizard()).toBe(true);

      component.dismissWelcomeWizard();
      expect(component.welcomeWizardDismissed()).toBe(true);
      expect(localStorage.getItem('fintech_welcome_wizard_dismissed')).toBe('true');
      expect(component.showWelcomeWizard()).toBe(false);

      component.reopenWelcomeWizard();
      expect(component.welcomeWizardDismissed()).toBe(false);
      expect(component.showWelcomeWizard()).toBe(true);
    });
  });
});
