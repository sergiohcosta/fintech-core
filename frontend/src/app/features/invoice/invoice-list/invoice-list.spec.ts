import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection, LOCALE_ID } from '@angular/core';
import { provideRouter, ActivatedRoute } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';

import { InvoiceList } from './invoice-list';
import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { InvoicesService } from '../../../core/api/invoices/invoices.service';
import { AccountResponse, InvoiceResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

registerLocaleData(localePt, 'pt-BR');

const makeRoute = (accountId: string | null) => ({
  snapshot: { queryParamMap: { get: (key: string) => key === 'accountId' ? accountId : null } }
});

const mockCcAccount: AccountResponse = {
  id: 'cc-1', name: 'Nubank', type: 'CREDIT_CARD',
  countInLiquidBalance: false, countInNetWorth: true, active: true, balance: 0
};
const mockCcAccount2: AccountResponse = {
  id: 'cc-2', name: 'Itaú', type: 'CREDIT_CARD',
  countInLiquidBalance: false, countInNetWorth: true, active: true, balance: 0
};
const mockCheckingAccount: AccountResponse = {
  id: 'ch-1', name: 'Bradesco', type: 'CHECKING',
  countInLiquidBalance: true, countInNetWorth: true, active: true, balance: 1000
};
const mockInvoice: InvoiceResponseDTO = {
  id: 'inv-1', accountId: 'cc-1', accountName: 'Nubank',
  referenceMonth: 6, referenceYear: 2026, label: 'Junho/2026',
  closingDate: '2026-06-20', dueDate: '2026-07-05',
  status: 'OPEN', totalAmount: 500, transactionCount: 3
};

describe('InvoiceList', () => {
  let accountsService: AccountsService;
  let invoicesService: InvoicesService;

  function setup(accountId: string | null = null) {
    TestBed.configureTestingModule({
      imports: [InvoiceList, NoopAnimationsModule],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'pt-BR' },
        { provide: ActivatedRoute, useValue: makeRoute(accountId) }
      ]
    });
    accountsService = TestBed.inject(AccountsService);
    invoicesService = TestBed.inject(InvoicesService);
  }

  it('filtra apenas contas CREDIT_CARD', async () => {
    setup();
    vi.spyOn(accountsService, 'listAccounts').mockReturnValue(
      of([mockCcAccount, mockCheckingAccount]) as any
    );
    vi.spyOn(invoicesService, 'listInvoices').mockReturnValue(of([]) as any);

    const fixture = TestBed.createComponent(InvoiceList);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.creditCardAccounts()).toHaveLength(1);
    expect(fixture.componentInstance.creditCardAccounts()[0].name).toBe('Nubank');
  });

  it('exibe empty state quando há múltiplos cartões e nenhum selecionado', async () => {
    setup();
    vi.spyOn(accountsService, 'listAccounts').mockReturnValue(of([mockCcAccount, mockCcAccount2]) as any);

    const fixture = TestBed.createComponent(InvoiceList);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('Selecione um cartão');
  });

  it('auto-seleciona conta única quando há só um cartão de crédito', async () => {
    setup();
    vi.spyOn(accountsService, 'listAccounts').mockReturnValue(of([mockCcAccount]) as any);
    vi.spyOn(invoicesService, 'listInvoices').mockReturnValue(of([mockInvoice]) as any);

    const fixture = TestBed.createComponent(InvoiceList);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.selectedId()).toBe('cc-1');
    expect(fixture.componentInstance.invoices()).toHaveLength(1);
  });

  it('pré-seleciona conta quando ?accountId está na URL', async () => {
    setup('cc-1');
    vi.spyOn(accountsService, 'listAccounts').mockReturnValue(of([mockCcAccount]) as any);
    vi.spyOn(invoicesService, 'listInvoices').mockReturnValue(of([mockInvoice]) as any);

    const fixture = TestBed.createComponent(InvoiceList);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.selectedId()).toBe('cc-1');
    expect(fixture.componentInstance.invoices()).toHaveLength(1);
  });

  it('carrega faturas quando conta é selecionada', async () => {
    setup();
    vi.spyOn(accountsService, 'listAccounts').mockReturnValue(of([mockCcAccount]) as any);
    vi.spyOn(invoicesService, 'listInvoices').mockReturnValue(of([mockInvoice]) as any);

    const fixture = TestBed.createComponent(InvoiceList);
    fixture.detectChanges();
    await fixture.whenStable();

    fixture.componentInstance.selectedId.set('cc-1');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.invoices()).toHaveLength(1);
    expect(fixture.componentInstance.invoices()[0].label).toBe('Junho/2026');
  });

  it('statusChipClass retorna classe correta para cada status', async () => {
    setup();
    vi.spyOn(accountsService, 'listAccounts').mockReturnValue(of([]) as any);

    const fixture = TestBed.createComponent(InvoiceList);
    fixture.detectChanges();
    const comp = fixture.componentInstance;

    expect(comp.statusChipClass('OPEN')).toContain('status-open');
    expect(comp.statusChipClass('CLOSED')).toContain('status-closed');
    expect(comp.statusChipClass('PAID')).toContain('status-paid');
  });
});
