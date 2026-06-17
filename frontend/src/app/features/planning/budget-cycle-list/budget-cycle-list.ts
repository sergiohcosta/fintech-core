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
import { AuthService } from '../../../core/services/auth';
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
  private readonly authService = inject(AuthService);
  private readonly snackBar = inject(MatSnackBar);

  readonly cycles = signal<BudgetCycleResponse[]>([]);
  readonly loading = signal(true);
  readonly deleting = signal<string | null>(null);
  readonly isAdmin = this.authService.isAdmin;

  displayedColumns = ['period', 'openingBalance', 'status', 'actions'];

  ngOnInit(): void {
    this.planningService.listCycles()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: page => this.cycles.set(page.content ?? []),
        error: () => this.snackBar.open('Erro ao carregar ciclos.', 'OK', { duration: 3000 }),
      });
  }

  requestDelete(id: string): void {
    this.deleting.set(id);
  }

  cancelDelete(): void {
    this.deleting.set(null);
  }

  confirmDelete(id: string): void {
    this.planningService.deleteCycle(id).subscribe({
      next: () => {
        this.cycles.update(cs => cs.filter(c => c.id !== id));
        this.deleting.set(null);
        this.snackBar.open('Ciclo excluído.', 'OK', { duration: 2000 });
      },
      error: () => {
        this.deleting.set(null);
        this.snackBar.open('Erro ao excluir ciclo.', 'OK', { duration: 3000 });
      },
    });
  }
}
