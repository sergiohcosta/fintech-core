import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';

import { filter, finalize, forkJoin, switchMap } from 'rxjs';

import { PlanningService } from '../planning.service';
import { RecurringBudgetItemRequest, RecurringBudgetItemResponse } from '../../../core/api/fintechSaaSAPI.schemas';
import { RecurringItemFormComponent } from '../recurring-item-form/recurring-item-form';

@Component({
  selector: 'app-recurring-item-list',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe,
    MatButtonModule, MatIconModule, MatSlideToggleModule, MatSnackBarModule,
    MatTableModule, MatTooltipModule,
  ],
  templateUrl: './recurring-item-list.html',
  styleUrl: './recurring-item-list.scss',
})
export class RecurringItemList implements OnInit {
  private readonly planningService = inject(PlanningService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  readonly items = signal<RecurringBudgetItemResponse[]>([]);
  readonly inactiveItems = signal<RecurringBudgetItemResponse[]>([]);
  readonly loading = signal(true);
  readonly showInactive = signal(false);

  displayedColumns = ['day', 'description', 'type', 'amount', 'actions'];

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    forkJoin({
      active: this.planningService.listRecurring(true),
      inactive: this.planningService.listRecurring(false),
    })
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: ({ active, inactive }) => {
          this.items.set(active);
          this.inactiveItems.set(inactive);
        },
        error: () => this.snackBar.open('Erro ao carregar templates.', 'OK', { duration: 3000 }),
      });
  }

  openForm(existing?: RecurringBudgetItemResponse): void {
    const ref = this.dialog.open(RecurringItemFormComponent, {
      width: '460px',
      data: existing ?? null,
    });
    ref.afterClosed().pipe(
      filter(Boolean),
      switchMap((result: RecurringBudgetItemRequest) => existing
        ? this.planningService.updateRecurring(existing.id!, result)
        : this.planningService.createRecurring(result)
      )
    ).subscribe({
      next: () => {
        this.load();
        this.snackBar.open(existing ? 'Template atualizado.' : 'Template criado.', 'OK', { duration: 2000 });
      },
      error: () => this.snackBar.open('Erro ao salvar template.', 'OK', { duration: 3000 }),
    });
  }

  deactivate(item: RecurringBudgetItemResponse): void {
    this.planningService.deleteRecurring(item.id!).subscribe({
      next: () => {
        this.items.update(list => list.filter(i => i.id !== item.id));
        this.inactiveItems.update(list => [...list, { ...item, active: false }]);
        this.snackBar.open('Template desativado.', 'OK', { duration: 2000 });
      },
    });
  }

  reactivate(item: RecurringBudgetItemResponse): void {
    this.planningService.reactivateRecurring(item.id!).subscribe({
      next: reactivated => {
        this.inactiveItems.update(list => list.filter(i => i.id !== item.id));
        this.items.update(list => [...list, reactivated]);
        this.snackBar.open('Template reativado.', 'OK', { duration: 2000 });
      },
      error: () => this.snackBar.open('Erro ao reativar template.', 'OK', { duration: 3000 }),
    });
  }
}
