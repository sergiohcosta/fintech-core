import { TestBed } from '@angular/core/testing';
import { MAT_BOTTOM_SHEET_DATA, MatBottomSheetRef } from '@angular/material/bottom-sheet';
import { describe, it, expect, vi } from 'vitest';
import { TransactionActionSheetComponent, TransactionActionSheetData } from './transaction-action-sheet';

describe('TransactionActionSheetComponent', () => {
  it('retorna a decisão de confirmar ou cancelar', async () => {
    const dismiss = vi.fn();
    const data: TransactionActionSheetData = {
      title: 'Excluir transação?',
      message: 'Esta ação não pode ser desfeita.',
      confirmText: 'Excluir',
    };

    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [TransactionActionSheetComponent],
      providers: [
        { provide: MAT_BOTTOM_SHEET_DATA, useValue: data },
        { provide: MatBottomSheetRef, useValue: { dismiss } },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(TransactionActionSheetComponent);
    fixture.componentInstance.cancel();
    fixture.componentInstance.confirm();

    expect(dismiss).toHaveBeenNthCalledWith(1, false);
    expect(dismiss).toHaveBeenNthCalledWith(2, true);
  });
});
