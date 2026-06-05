import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection, LOCALE_ID } from '@angular/core';
import { provideRouter, ActivatedRoute } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatDialogModule } from '@angular/material/dialog';
import { of } from 'rxjs';
import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';

import { InvoiceDetail } from './invoice-detail';
import { InvoicesService } from '../../../core/api/invoices/invoices.service';
import { TransactionsService } from '../../../core/api/transactions/transactions.service';
import { InvoiceResponseDTO, TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

registerLocaleData(localePt, 'pt-BR');

const makeRoute = (id: string) => ({
  snapshot: { paramMap: { get: (_: string) => id } }
});

const baseInvoice: InvoiceResponseDTO = {
  id: 'inv-1', accountId: 'cc-1', accountName: 'Nubank',
  referenceMonth: 6, referenceYear: 2026, label: 'Junho/2026',
  closingDate: '2026-06-20', dueDate: '2026-07-05',
  status: 'OPEN', totalAmount: 800, transactionCount: 3
};

const makeTransaction = (overrides: Partial<TransactionResponseDTO>): TransactionResponseDTO => ({
  id: 't1', description: 'Compra', amount: 100, date: '2026-06-01',
  type: 'EXPENSE', status: 'PAID',
  installmentLabel: null, categoryName: 'Alimentação', categoryId: 'cat-1',
  categoryArchived: false, accountName: 'Nubank', accountId: 'cc-1',
  transferId: null, installmentGroupId: null, installmentGroupDescription: null,
  installmentNumber: null, totalInstallments: null,
  invoiceId: 'inv-1', invoiceDueDate: '2026-07-05', invoiceStatus: 'OPEN',
  ...overrides
});

describe('InvoiceDetail', () => {
  let invoicesService: InvoicesService;
  let transactionsService: TransactionsService;

  function setup(invoiceId = 'inv-1') {
    TestBed.configureTestingModule({
      imports: [InvoiceDetail, NoopAnimationsModule, MatDialogModule],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'pt-BR' },
        { provide: ActivatedRoute, useValue: makeRoute(invoiceId) }
      ]
    });
    invoicesService = TestBed.inject(InvoicesService);
    transactionsService = TestBed.inject(TransactionsService);
  }

  it('totalIncome soma apenas transações INCOME não canceladas', async () => {
    setup();
    vi.spyOn(invoicesService, 'getInvoice').mockReturnValue(of(baseInvoice) as any);
    vi.spyOn(transactionsService, 'listTransactions').mockReturnValue(of([
      makeTransaction({ id: 't1', type: 'INCOME',  status: 'PAID',      amount: 200 }),
      makeTransaction({ id: 't2', type: 'INCOME',  status: 'CANCELLED', amount: 100 }),
      makeTransaction({ id: 't3', type: 'EXPENSE', status: 'PAID',      amount: 500 })
    ]) as any);

    const fixture = TestBed.createComponent(InvoiceDetail);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.totalIncome()).toBe(200);
  });

  it('totalExpense soma apenas transações EXPENSE não canceladas', async () => {
    setup();
    vi.spyOn(invoicesService, 'getInvoice').mockReturnValue(of(baseInvoice) as any);
    vi.spyOn(transactionsService, 'listTransactions').mockReturnValue(of([
      makeTransaction({ id: 't1', type: 'EXPENSE', status: 'PAID',      amount: 500 }),
      makeTransaction({ id: 't2', type: 'EXPENSE', status: 'CANCELLED', amount: 200 }),
      makeTransaction({ id: 't3', type: 'INCOME',  status: 'PAID',      amount: 50  })
    ]) as any);

    const fixture = TestBed.createComponent(InvoiceDetail);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.totalExpense()).toBe(500);
  });

  it('netBalance = totalIncome - totalExpense', async () => {
    setup();
    vi.spyOn(invoicesService, 'getInvoice').mockReturnValue(of(baseInvoice) as any);
    vi.spyOn(transactionsService, 'listTransactions').mockReturnValue(of([
      makeTransaction({ id: 't1', type: 'INCOME',  amount: 80,  status: 'PAID' }),
      makeTransaction({ id: 't2', type: 'EXPENSE', amount: 500, status: 'PAID' })
    ]) as any);

    const fixture = TestBed.createComponent(InvoiceDetail);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.netBalance()).toBe(-420);
  });

  it('mostra botão "Fechar Fatura" apenas quando status OPEN', async () => {
    setup();
    vi.spyOn(invoicesService, 'getInvoice').mockReturnValue(of({ ...baseInvoice, status: 'OPEN' }) as any);
    vi.spyOn(transactionsService, 'listTransactions').mockReturnValue(of([]) as any);

    const fixture = TestBed.createComponent(InvoiceDetail);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('Fechar Fatura');
    expect(fixture.nativeElement.textContent).not.toContain('Pagar Fatura');
  });

  it('mostra botão "Pagar Fatura" apenas quando status CLOSED', async () => {
    setup();
    vi.spyOn(invoicesService, 'getInvoice').mockReturnValue(of({ ...baseInvoice, status: 'CLOSED' }) as any);
    vi.spyOn(transactionsService, 'listTransactions').mockReturnValue(of([]) as any);

    const fixture = TestBed.createComponent(InvoiceDetail);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('Pagar Fatura');
    expect(fixture.nativeElement.textContent).not.toContain('Fechar Fatura');
  });

  it('não mostra botões de ação quando status PAID', async () => {
    setup();
    vi.spyOn(invoicesService, 'getInvoice').mockReturnValue(of({ ...baseInvoice, status: 'PAID' }) as any);
    vi.spyOn(transactionsService, 'listTransactions').mockReturnValue(of([]) as any);

    const fixture = TestBed.createComponent(InvoiceDetail);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).not.toContain('Fechar Fatura');
    expect(fixture.nativeElement.textContent).not.toContain('Pagar Fatura');
  });
});
