import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { filter, finalize, switchMap } from 'rxjs';

import { PlanningService } from '../planning.service';
import { RecurrenceRuleCreateDTO, RecurrenceRulePatchDTO, RecurrenceRuleResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';
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

  readonly items = signal<RecurrenceRuleResponseDTO[]>([]);
  readonly inactiveItems = signal<RecurrenceRuleResponseDTO[]>([]);
  readonly loading = signal(true);
  readonly showInactive = signal(false);

  displayedColumns = ['day', 'description', 'type', 'amount', 'actions'];

  ngOnInit(): void {
    this.load();
  }

  /** Extrai o dia do mês da string RRULE. Ex: "FREQ=MONTHLY;BYMONTHDAY=15" → "15". */
  dayFromRrule(rrule: string | null | undefined): string {
    if (!rrule) return '?';
    const match = rrule.match(/BYMONTHDAY=(-?\d+)/);
    return match ? (match[1] === '-1' ? 'último' : match[1]) : '?';
  }

  private load(): void {
    this.planningService.listRecurring()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: all => {
          this.items.set(all.filter(i => i.status === 'ACTIVE'));
          this.inactiveItems.set(all.filter(i => i.status !== 'ACTIVE'));
        },
        error: () => this.snackBar.open('Erro ao carregar recorrências.', 'OK', { duration: 3000 }),
      });
  }

  openForm(existing?: RecurrenceRuleResponseDTO): void {
    const ref = this.dialog.open(RecurringItemFormComponent, {
      width: '500px',
      data: existing ?? null,
    });
    ref.afterClosed().pipe(
      filter(Boolean),
      switchMap((result: RecurrenceRuleCreateDTO | RecurrenceRulePatchDTO) => existing
        ? this.planningService.updateRecurring(existing.id!, result as RecurrenceRulePatchDTO)
        : this.planningService.createRecurring(result as RecurrenceRuleCreateDTO)
      )
    ).subscribe({
      next: () => {
        this.load();
        this.snackBar.open(existing ? 'Recorrência atualizada.' : 'Recorrência criada.', 'OK', { duration: 2000 });
      },
      error: () => this.snackBar.open('Erro ao salvar recorrência.', 'OK', { duration: 3000 }),
    });
  }

  deactivate(item: RecurrenceRuleResponseDTO): void {
    this.planningService.deleteRecurring(item.id!).subscribe({
      next: () => {
        this.items.update(list => list.filter(i => i.id !== item.id));
        this.inactiveItems.update(list => [...list, { ...item, status: 'CANCELLED' as const }]);
        this.snackBar.open('Recorrência cancelada.', 'OK', { duration: 2000 });
      },
    });
  }

  reactivate(item: RecurrenceRuleResponseDTO): void {
    this.planningService.reactivateRecurring(item.id!).subscribe({
      next: (reactivated: RecurrenceRuleResponseDTO) => {
        this.inactiveItems.update(list => list.filter(i => i.id !== item.id));
        this.items.update(list => [...list, reactivated]);
        this.snackBar.open('Recorrência reativada.', 'OK', { duration: 2000 });
      },
      error: () => this.snackBar.open('Erro ao reativar recorrência.', 'OK', { duration: 3000 }),
    });
  }
}
