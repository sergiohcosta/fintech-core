import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { of } from 'rxjs';
import { vi } from 'vitest';

import { DashboardComponent } from './dashboard';
import { DashboardService } from '../../core/api/dashboard/dashboard.service';
import { TransactionsService } from '../../core/api/transactions/transactions.service';

describe('DashboardComponent', () => {
  let component: DashboardComponent;
  let fixture: ComponentFixture<DashboardComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    const dashboardService = TestBed.inject(DashboardService);
    const transactionsService = TestBed.inject(TransactionsService);
    vi.spyOn(dashboardService, 'getDashboardSummary').mockReturnValue(of({}) as any);
    vi.spyOn(transactionsService, 'listTransactions').mockReturnValue(of([]) as any);

    fixture = TestBed.createComponent(DashboardComponent);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
