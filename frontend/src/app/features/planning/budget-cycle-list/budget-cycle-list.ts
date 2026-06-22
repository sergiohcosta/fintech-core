import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { finalize } from 'rxjs/operators';
import { PlanningService } from '../planning.service';
import { BudgetCycleResponse } from '../../../core/api/fintechSaaSAPI.schemas';
import { ConfirmationDialogComponent, ConfirmationDialogData } from '../../../components/confirmation-dialog/confirmation-dialog';

@Component({
  selector: 'app-budget-cycle-list',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe, RouterLink,
    MatButtonModule, MatChipsModule, MatIconModule, MatSnackBarModule, MatTableModule, MatTooltipModule,
  ],
  templateUrl: './budget-cycle-list.html',
})
export class BudgetCycleList implements OnInit {
  private readonly planningService = inject(PlanningService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);

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

  deleteCycle(cycle: BudgetCycleResponse): void {
    const fmt = (d: string) => d.split('-').reverse().join('/');
    const ref = this.dialog.open(ConfirmationDialogComponent, {
      data: {
        title: 'Excluir ciclo',
        message: `Excluir o ciclo de ${fmt(cycle.startDate!)} a ${fmt(cycle.endDate!)}? Esta ação não pode ser desfeita.`,
        confirmText: 'Excluir',
        cancelText: 'Cancelar',
      } satisfies ConfirmationDialogData,
    });
    ref.afterClosed().subscribe((confirmed: boolean) => {
      if (!confirmed) return;
      this.planningService.deleteCycle(cycle.id!)
        .subscribe({
          next: () => this.cycles.update(list => list.filter(c => c.id !== cycle.id)),
          error: () => this.snackBar.open('Erro ao excluir ciclo.', 'OK', { duration: 3000 }),
        });
    });
  }
}
