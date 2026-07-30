import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideNativeDateAdapter } from '@angular/material/core';
import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { of } from 'rxjs';
import { InvoicePayDialogComponent } from './invoice-pay-dialog';
import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { AccountResponse, AccountType, InvoiceResponseDTO, InvoiceStatus } from '../../../core/api/fintechSaaSAPI.schemas';
import { formatLocalDate } from '../../transaction/transaction-form/transaction-form.utils';

registerLocaleData(localePt, 'pt-BR');

describe('InvoicePayDialogComponent', () => {
  let fixture: ComponentFixture<InvoicePayDialogComponent>;
  let component: InvoicePayDialogComponent;
  let accountsSvc: { listAccounts: ReturnType<typeof vi.fn> };
  let dialogRef: { close: ReturnType<typeof vi.fn> };

  const invoice: InvoiceResponseDTO = {
    id: 'inv-1', accountId: 'acc-cc', accountName: 'Cartão Nubank',
    referenceMonth: 6, referenceYear: 2026, label: 'Junho/2026',
    closingDate: '2026-06-05', dueDate: '2026-06-15',
    status: InvoiceStatus.CLOSED, totalAmount: 350, transactionCount: 3,
  };

  const checkingAccount: AccountResponse = {
    id: 'acc-checking', name: 'Conta Corrente', type: AccountType.CHECKING,
    countInLiquidBalance: true, countInNetWorth: true, active: true, balance: 1000,
  };

  beforeEach(async () => {
    accountsSvc = { listAccounts: vi.fn().mockReturnValue(of([checkingAccount])) };
    dialogRef = { close: vi.fn() };

    await TestBed.configureTestingModule({
      imports: [InvoicePayDialogComponent],
      providers: [
        provideAnimationsAsync(),
        provideNativeDateAdapter(),
        { provide: AccountsService, useValue: accountsSvc },
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: { invoice } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(InvoicePayDialogComponent);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('sugere a data corrente ao abrir', () => {
    expect(formatLocalDate(component.paymentDate()!)).toBe(formatLocalDate(new Date()));
  });

  it('confirm fecha o dialog com sourceAccountId e paymentDate=hoje quando o usuário não altera a data', () => {
    component.selectedAccountId.set(checkingAccount.id);
    component.confirm();

    expect(dialogRef.close).toHaveBeenCalledWith({
      sourceAccountId: checkingAccount.id,
      paymentDate: formatLocalDate(new Date()),
    });
  });

  it('confirm não fecha o dialog sem conta selecionada', () => {
    component.confirm();
    expect(dialogRef.close).not.toHaveBeenCalled();
  });

  it('confirm não fecha o dialog sem data de pagamento', () => {
    component.selectedAccountId.set(checkingAccount.id);
    component.paymentDate.set(null);
    component.confirm();
    expect(dialogRef.close).not.toHaveBeenCalled();
  });

  it('maxDate é hoje, espelhando a rejeição de data futura do backend', () => {
    expect(formatLocalDate(component.maxDate)).toBe(formatLocalDate(new Date()));
  });
});
