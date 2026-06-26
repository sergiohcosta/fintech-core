import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { finalize } from 'rxjs/operators';
import { PlanningService } from '../planning.service';
import { BudgetCycleResponse, BudgetCycleSummary, BudgetItemResponse } from '../../../core/api/fintechSaaSAPI.schemas';
import { DEFAULT_SUMMARY } from '../budget-cycle-current/budget-cycle.utils';
import { BreakdownDialogComponent } from '../breakdown/breakdown-dialog';
import { buildBreakdown, BreakdownCard } from '../breakdown/budget-cycle-breakdown';

@Component({
  selector: 'app-budget-cycle-detail',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe,
    MatButtonModule, MatCardModule, MatChipsModule, MatIconModule,
    MatSnackBarModule, MatTableModule, MatTooltipModule,
  ],
  templateUrl: './budget-cycle-detail.html',
})
export class BudgetCycleDetail implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly planningService = inject(PlanningService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);

  readonly cycle = signal<BudgetCycleResponse | null>(null);
  readonly items = signal<BudgetItemResponse[]>([]);
  readonly loading = signal(true);

  readonly summary = computed((): Required<BudgetCycleSummary> => ({
    ...DEFAULT_SUMMARY,
    ...(this.cycle()?.summary ?? {}),
  }));

  displayedColumns = ['description', 'expectedDate', 'amount', 'type', 'status'];

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id')!;
    this.planningService.getCycle(id)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: c => { this.cycle.set(c); this.items.set(c.items ?? []); },
        error: () => this.snackBar.open('Erro ao carregar ciclo.', 'OK', { duration: 3000 }),
      });
  }

  openBreakdown(card: BreakdownCard): void {
    const c = this.cycle();
    if (!c) return;
    this.dialog.open(BreakdownDialogComponent, {
      width: '480px',
      data: buildBreakdown(card, {
        openingBalance: c.openingBalance ?? 0,
        summary: this.summary(),
        items: this.items(),
        unplanned: c.unplannedTransactions ?? [],
      }),
    });
  }
}
