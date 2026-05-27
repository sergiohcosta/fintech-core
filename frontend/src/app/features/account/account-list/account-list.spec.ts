import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { AccountList } from './account-list';
import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { provideZonelessChangeDetection, LOCALE_ID } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatDialogModule } from '@angular/material/dialog';
import { of } from 'rxjs';
import { AccountResponse } from '../../../core/api/fintechSaaSAPI.schemas';
import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
registerLocaleData(localePt, 'pt-BR');

describe('AccountList', () => {
  let accountsService: AccountsService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AccountList, NoopAnimationsModule, MatDialogModule],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'pt-BR' }
      ]
    });
    accountsService = TestBed.inject(AccountsService);
  });

  it('exibe contas retornadas pelo serviço', async () => {
    const mockAccounts: AccountResponse[] = [
      { id: '1', name: 'Bradesco', type: 'CHECKING', countInLiquidBalance: true,
        countInNetWorth: true, active: true, balance: 1500 }
    ];
    vi.spyOn(accountsService, 'listAccounts').mockReturnValue(of(mockAccounts) as any);

    const fixture = TestBed.createComponent(AccountList);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.accounts()).toHaveLength(1);
    expect(fixture.componentInstance.accounts()[0].name).toBe('Bradesco');
  });

  it('typeLabel retorna rótulo correto para cada tipo', () => {
    const fixture = TestBed.createComponent(AccountList);
    const component = fixture.componentInstance;

    expect(component.typeLabel('CHECKING')).toBe('Conta Corrente');
    expect(component.typeLabel('INVESTMENT')).toBe('Investimento');
    expect(component.typeLabel('CREDIT_CARD')).toBe('Cartão de Crédito');
    expect(component.typeLabel('CASH')).toBe('Carteira');
  });
});
