import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatBottomSheetRef } from '@angular/material/bottom-sheet';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, it, expect, beforeEach, vi } from 'vitest';

import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { CategoriesService } from '../../../core/api/categories/categories.service';
import { AccountResponse, CategoryResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';
import { TransactionsService } from '../../../core/api/transactions/transactions.service';
import { TransfersService } from '../../../core/api/transfers/transfers.service';
import { TransactionEventsService } from '../../../core/services/transaction-events';
import { TransactionQuickSheetComponent } from './transaction-quick-sheet';

describe('TransactionQuickSheetComponent', () => {
  let fixture: ComponentFixture<TransactionQuickSheetComponent>;
  let component: TransactionQuickSheetComponent;
  let transactionService: { createTransaction: ReturnType<typeof vi.fn> };
  let transferService: { createTransfer: ReturnType<typeof vi.fn> };
  let sheetRef: { dismiss: ReturnType<typeof vi.fn> };
  let snackBar: { open: ReturnType<typeof vi.fn> };
  let router: { navigate: ReturnType<typeof vi.fn> };
  let dialog: { open: ReturnType<typeof vi.fn> };

  const accounts: AccountResponse[] = [
    {
      id: 'account-1', name: 'Conta corrente', type: 'CHECKING', active: true,
      countInLiquidBalance: true, countInNetWorth: true, balance: 250,
    },
    {
      id: 'account-2', name: 'Carteira', type: 'CASH', active: true,
      countInLiquidBalance: true, countInNetWorth: true, balance: 50,
    },
    {
      id: 'account-inactive', name: 'Conta encerrada', type: 'CHECKING', active: false,
      countInLiquidBalance: false, countInNetWorth: false, balance: 0,
    },
  ];
  const categories: CategoryResponseDTO[] = [
    {
      id: 'category-1', name: 'Alimentação', icon: 'restaurant', color: '#008000',
      archived: false, children: [],
    },
  ];

  beforeEach(async () => {
    transactionService = { createTransaction: vi.fn(() => of([])) };
    transferService = { createTransfer: vi.fn(() => of({})) };
    sheetRef = { dismiss: vi.fn() };
    snackBar = { open: vi.fn() };
    router = { navigate: vi.fn().mockResolvedValue(true) };
    dialog = { open: vi.fn(() => ({ afterClosed: () => of(true) })) };

    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [TransactionQuickSheetComponent],
      providers: [
        { provide: AccountsService, useValue: { listAccounts: () => of(accounts) } },
        { provide: CategoriesService, useValue: { listCategories: () => of(categories) } },
        { provide: TransactionsService, useValue: transactionService },
        { provide: TransfersService, useValue: transferService },
        { provide: MatBottomSheetRef, useValue: sheetRef },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: Router, useValue: router },
        { provide: MatDialog, useValue: dialog },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(TransactionQuickSheetComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
  });

  it('cria uma despesa, fecha a sheet e sinaliza a atualização', () => {
    const testDate = new Date(2026, 9, 19);
    component.form.setValue({
      amount: 100,
      type: 'EXPENSE',
      date: testDate,
      accountId: 'account-1',
      destinationAccountId: '',
      categoryId: 'category-1',
      description: '',
    });

    component.submit();

    expect(transactionService.createTransaction).toHaveBeenCalledWith(expect.objectContaining({
      amount: 100,
      type: 'EXPENSE',
      date: '2026-10-19',
      accountId: 'account-1',
      categoryId: 'category-1',
      description: 'Lançamento rápido',
    }));
    expect(sheetRef.dismiss).toHaveBeenCalledOnce();
    expect(snackBar.open).toHaveBeenCalledWith('Transação lançada', 'OK', { duration: 3000 });
    expect(TestBed.inject(TransactionEventsService).refreshTrigger()).toBe(1);
  });

  it('inicializa com a data de hoje e permite alternar para ontem pelo atalho', () => {
    expect(component.isToday()).toBe(true);
    expect(component.isYesterday()).toBe(false);

    component.setDateYesterday();

    expect(component.isToday()).toBe(false);
    expect(component.isYesterday()).toBe(true);

    component.setDateToday();

    expect(component.isToday()).toBe(true);
    expect(component.isYesterday()).toBe(false);
  });

  it('usa o serviço de transferências e impede a mesma conta nos dois lados', () => {
    component.form.controls.type.setValue('TRANSFER');
    component.form.controls.amount.setValue(20);
    component.form.controls.accountId.setValue('account-1');
    component.form.controls.destinationAccountId.setValue('account-1');
    expect(component.form.invalid).toBe(true);

    component.form.controls.destinationAccountId.setValue('account-2');
    expect(component.form.valid).toBe(true);
    component.submit();

    expect(transferService.createTransfer).toHaveBeenCalledWith(expect.objectContaining({
      fromAccountId: 'account-1',
      toAccountId: 'account-2',
      amount: 20,
    }));
    expect(transactionService.createTransaction).not.toHaveBeenCalled();
    expect(sheetRef.dismiss).toHaveBeenCalledOnce();
  });

  it('limpa a categoria selecionada ao trocar o tipo do lançamento', () => {
    component.form.controls.categoryId.setValue('category-1');

    component.form.controls.type.setValue('INCOME');

    expect(component.form.controls.categoryId.value).toBe('');
    expect(component.availableCategories()).toHaveLength(1);
  });

  it('fecha ao arrastar o handle para baixo além do limite', () => {
    component.startDrag(new PointerEvent('pointerdown', { pointerId: 1, clientY: 20 }));
    component.moveDrag(new PointerEvent('pointermove', { pointerId: 1, clientY: 140 }));
    component.endDrag(new PointerEvent('pointerup', { pointerId: 1, clientY: 140 }));

    expect(sheetRef.dismiss).toHaveBeenCalledOnce();
    expect(component.dragOffset()).toBe(0);
  });

  it('fecha a sheet e abre o fluxo existente de importação', () => {
    component.openImport();

    expect(sheetRef.dismiss).toHaveBeenCalledOnce();
    expect(router.navigate).toHaveBeenCalledWith(['/import']);
    expect(dialog.open).not.toHaveBeenCalled();
  });

  it('pede confirmação antes de descartar um rascunho ao abrir a importação', () => {
    component.form.controls.description.setValue('Mercado');
    component.form.controls.description.markAsDirty();

    component.openImport();

    expect(dialog.open).toHaveBeenCalled();
    expect(sheetRef.dismiss).toHaveBeenCalledOnce();
    expect(router.navigate).toHaveBeenCalledWith(['/import']);
  });

  it('mantém a sheet aberta se a API rejeitar o lançamento', () => {
    transactionService.createTransaction.mockReturnValue(throwError(() => new Error('falha')));
    component.form.setValue({
      amount: 100,
      type: 'EXPENSE',
      date: new Date(),
      accountId: 'account-1',
      destinationAccountId: '',
      categoryId: 'category-1',
      description: 'Compra',
    });

    component.submit();

    expect(sheetRef.dismiss).not.toHaveBeenCalled();
    expect(snackBar.open).toHaveBeenCalledWith(
      'Não foi possível lançar. Tente novamente.',
      'OK',
      { duration: 5000 },
    );
  });
});
