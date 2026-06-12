import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { finalize } from 'rxjs/operators';
import { PlanningService } from '../planning.service';
import { BudgetCycleResponse } from '../../../core/api/fintechSaaSAPI.schemas';

@Component({
  selector: 'app-budget-cycle-list',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe, RouterLink,
    MatButtonModule, MatChipsModule, MatIconModule, MatSnackBarModule, MatTableModule,
  ],
  templateUrl: './budget-cycle-list.html',
})
export class BudgetCycleList implements OnInit {
  private readonly planningService = inject(PlanningService);
  private readonly snackBar = inject(MatSnackBar);

  readonly cycles = signal<BudgetCycleResponse[]>([]);
  readonly loading = signal(true);

  displayedColumns = ['period', 'openingBalance', 'status', 'actions'];

  ngOnInit(): void {
    this.planningService.listCycles()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: page => this.cycles.set(page.content ?? []),
        error: () => this.snackBar.open('Erro ao carregar ciclos.', 'OK', { duration: 3000 }),
      });
  }
}
