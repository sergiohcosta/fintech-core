import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatTableModule } from '@angular/material/table';
import { PlanningService } from '../planning.service';
import { BudgetCycleResponse, BudgetItemResponse } from '../../../core/api/fintechSaaSAPI.schemas';
import { buildSummary } from '../budget-cycle-current/budget-cycle.utils';

@Component({
  selector: 'app-budget-cycle-detail',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe,
    MatButtonModule, MatCardModule, MatChipsModule, MatTableModule,
  ],
  templateUrl: './budget-cycle-detail.html',
})
export class BudgetCycleDetail implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly planningService = inject(PlanningService);

  readonly cycle = signal<BudgetCycleResponse | null>(null);
  readonly items = signal<BudgetItemResponse[]>([]);
  readonly loading = signal(true);

  readonly summary = computed(() =>
    buildSummary(this.items(), this.cycle()?.openingBalance ?? 0));

  displayedColumns = ['description', 'expectedDate', 'amount', 'type', 'status'];

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id')!;
    this.planningService.getCycle(id).subscribe({
      next: c => { this.cycle.set(c); this.items.set(c.items ?? []); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }
}
