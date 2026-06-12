import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { PlanningService } from '../planning.service';
import { BudgetCycleResponse } from '../../../core/api/fintechSaaSAPI.schemas';

@Component({
  selector: 'app-budget-cycle-list',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe, RouterLink,
    MatButtonModule, MatChipsModule, MatIconModule, MatTableModule,
  ],
  templateUrl: './budget-cycle-list.html',
})
export class BudgetCycleList implements OnInit {
  private readonly planningService = inject(PlanningService);

  readonly cycles = signal<BudgetCycleResponse[]>([]);
  readonly loading = signal(true);

  displayedColumns = ['period', 'openingBalance', 'status', 'actions'];

  ngOnInit(): void {
    this.planningService.listCycles().subscribe({
      next: page => { this.cycles.set(page.content ?? []); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }
}
