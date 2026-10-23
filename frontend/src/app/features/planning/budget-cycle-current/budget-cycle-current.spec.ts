import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection, LOCALE_ID } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';

import { BudgetCycleCurrentComponent } from './budget-cycle-current';
import { PlanningService } from '../planning.service';
import { BudgetCycleResponse } from '../../../core/api/fintechSaaSAPI.schemas';
import { BudgetCycleOpenDialogComponent } from '../budget-cycle-open-dialog/budget-cycle-open-dialog';

registerLocaleData(localePt, 'pt-BR');

describe('BudgetCycleCurrentComponent', () => {
  let planningService: PlanningService;
  let dialog: MatDialog;

  const mockCycle: BudgetCycleResponse = {
    id: 'cycle-1',
    startDate: '2026-10-01',
    endDate: '2026-10-31',
    status: 'OPEN',
    openingBalance: 2000,
    items: [
      { id: 'item-1', description: 'Salário', amount: 5000, type: 'INCOME', status: 'REALIZED' },
      { id: 'item-2', description: 'Aluguel', amount: 1500, type: 'EXPENSE', status: 'PENDING', source: 'RECURRING' },
      { id: 'item-3', description: 'Notebook', amount: 300, type: 'EXPENSE', status: 'PENDING', source: 'INSTALLMENT' },
    ],
    summary: {
      plannedIncome: 5000,
      realizedIncome: 5000,
      plannedExpense: 1800,
      realizedExpense: 600,
      dailyAllowance: 120,
      remainingDays: 10,
    },
    unplannedTransactions: [],
  };

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [BudgetCycleCurrentComponent, NoopAnimationsModule],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'pt-BR' },
      ],
    });
    planningService = TestBed.inject(PlanningService);
    dialog = TestBed.inject(MatDialog);
  });

  it('carrega o ciclo corrente e distribui itens por tipo', async () => {
    vi.spyOn(planningService, 'getCurrentCycle').mockReturnValue(of(mockCycle) as any);

    const fixture = TestBed.createComponent(BudgetCycleCurrentComponent);
    fixture.detectChanges();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    expect(comp.loading()).toBe(false);
    expect(comp.cycle()?.id).toBe('cycle-1');
    expect(comp.incomeItems()).toHaveLength(1);
    expect(comp.expenseItems()).toHaveLength(1);
    expect(comp.installmentItems()).toHaveLength(1);
  });

  it('calcula métricas de tempo e progresso do ciclo', async () => {
    vi.spyOn(planningService, 'getCurrentCycle').mockReturnValue(of(mockCycle) as any);

    const fixture = TestBed.createComponent(BudgetCycleCurrentComponent);
    fixture.detectChanges();
    await fixture.whenStable();

    const comp = fixture.componentInstance;
    expect(comp.cycleDaysTotal()).toBe(31);
    expect(comp.daysRemaining()).toBe(10);
    expect(comp.daysElapsed()).toBe(21);
    expect(comp.timeProgress()).toBeCloseTo((21 / 31) * 100, 1);
    expect(comp.incomeProgress()).toBe(100);
    expect(comp.expenseProgress()).toBeCloseTo((600 / 1800) * 100, 1);
  });

  it('mapeia rótulos e classes de status amigáveis', () => {
    const fixture = TestBed.createComponent(BudgetCycleCurrentComponent);
    const comp = fixture.componentInstance;

    expect(comp.statusLabel('PENDING')).toBe('Aguardando');
    expect(comp.statusLabel('REALIZED')).toBe('Realizado');
    expect(comp.statusLabel('SKIPPED')).toBe('Ignorado');
    expect(comp.statusLabel('INSTALLMENT')).toBe('Parcela');

    expect(comp.statusClass('REALIZED')).toBe('status-realized');
    expect(comp.statusClass('PENDING')).toBe('status-pending');
    expect(comp.statusClass('SKIPPED')).toBe('status-skipped');
  });

  it('abre BudgetCycleOpenDialogComponent ao acionar openCycle()', async () => {
    vi.spyOn(planningService, 'getCurrentCycle').mockReturnValue(of(mockCycle) as any);
    const openSpy = vi.spyOn(dialog, 'open').mockReturnValue({
      afterClosed: () => of(true),
    } as any);

    const fixture = TestBed.createComponent(BudgetCycleCurrentComponent);
    fixture.detectChanges();
    await fixture.whenStable();

    fixture.componentInstance.openCycle();

    expect(openSpy).toHaveBeenCalledWith(
      BudgetCycleOpenDialogComponent,
      expect.objectContaining({ width: '640px' })
    );
  });
});
