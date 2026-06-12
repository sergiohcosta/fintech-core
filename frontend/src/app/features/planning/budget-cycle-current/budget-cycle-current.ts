import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { HttpErrorResponse } from '@angular/common/http';
import { finalize } from 'rxjs/operators';

import { PlanningService } from '../planning.service';
import { BudgetCycleOpenRequest, BudgetCycleResponse, BudgetItemResponse } from '../../../core/api/fintechSaaSAPI.schemas';
import { buildSummary } from './budget-cycle.utils';
import { BudgetItemFormComponent, BudgetItemFormResult } from '../budget-item-form/budget-item-form';
import { LinkTransactionDialogComponent, LinkTransactionDialogData } from '../link-transaction-dialog/link-transaction-dialog';

@Component({
  selector: 'app-budget-cycle-current',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe,
    MatButtonModule, MatCardModule, MatChipsModule, MatExpansionModule,
    MatIconModule, MatSnackBarModule, MatTableModule, MatTooltipModule,
  ],
  templateUrl: './budget-cycle-current.html',
  styleUrl: './budget-cycle-current.scss',
})
export class BudgetCycleCurrentComponent implements OnInit {
  private readonly planningService = inject(PlanningService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  readonly cycle = signal<BudgetCycleResponse | null>(null);
  readonly items = signal<BudgetItemResponse[]>([]);
  readonly loading = signal(true);
  readonly closing = signal(false);

  readonly incomeItems  = computed(() => this.items().filter(i => i.type === 'INCOME'));
  readonly expenseItems = computed(() => this.items().filter(i => i.type === 'EXPENSE' && i.source !== 'INSTALLMENT'));
  readonly installmentItems = computed(() => this.items().filter(i => i.source === 'INSTALLMENT'));
  readonly summary = computed(() => buildSummary(this.items(), this.cycle()?.openingBalance ?? 0));

  ngOnInit(): void {
    this.loadCurrentCycle();
  }

  private loadCurrentCycle(): void {
    this.loading.set(true);
    this.planningService.getCurrentCycle()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: c => {
          this.cycle.set(c);
          this.items.set(c.items ?? []);
        },
        error: (err: { status: number }) => {
          if (err.status === 404) {
            this.cycle.set(null);
          } else {
            this.snackBar.open('Erro ao carregar planejamento.', 'OK', { duration: 3000 });
          }
        },
      });
  }

  openCycle(): void {
    const ref = this.dialog.open(BudgetItemFormComponent, {
      width: '400px',
      data: { mode: 'openCycle' },
    });
    ref.afterClosed().subscribe((req?: BudgetCycleOpenRequest) => {
      if (!req) return;
      this.planningService.openCycle(req).subscribe({
        next: c => {
          this.cycle.set(c);
          this.items.set(c.items ?? []);
          this.snackBar.open('Ciclo aberto com sucesso.', 'OK', { duration: 3000 });
        },
        error: (err: HttpErrorResponse) => {
          const msg = err.error?.message ?? 'Erro ao abrir ciclo.';
          this.snackBar.open(msg, 'OK', { duration: 5000 });
        },
      });
    });
  }

  closeCycle(): void {
    const id = this.cycle()?.id;
    if (!id) return;
    this.closing.set(true);
    this.planningService.closeCycle(id)
      .pipe(finalize(() => this.closing.set(false)))
      .subscribe({
        next: c => {
          this.cycle.set(c);
          this.snackBar.open('Ciclo fechado.', 'OK', { duration: 3000 });
        },
        error: () => this.snackBar.open('Erro ao fechar ciclo.', 'OK', { duration: 3000 }),
      });
  }

  addItem(): void {
    const cycleId = this.cycle()?.id;
    if (!cycleId) return;
    const ref = this.dialog.open(BudgetItemFormComponent, {
      width: '500px',
      data: { cycleId },
    });
    ref.afterClosed().subscribe((result?: BudgetItemFormResult) => {
      if (!result) return;
      this.planningService.createItem(cycleId, result).subscribe({
        next: item => {
          this.items.update(list => [...list, item]);
          this.snackBar.open('Item adicionado.', 'OK', { duration: 2000 });
        },
        error: () => this.snackBar.open('Erro. Tente novamente.', 'OK', { duration: 3000 }),
      });
    });
  }

  linkTransaction(item: BudgetItemResponse): void {
    const cycleId = this.cycle()?.id;
    if (!cycleId) return;
    const ref = this.dialog.open(LinkTransactionDialogComponent, {
      width: '600px',
      data: { item, cycleId } satisfies LinkTransactionDialogData,
    });
    ref.afterClosed().subscribe((transactionId?: string) => {
      if (!transactionId) return;
      this.planningService.linkItem(item.id!, { transactionId }).subscribe({
        next: updated => this.replaceItem(updated),
        error: () => this.snackBar.open('Erro. Tente novamente.', 'OK', { duration: 3000 }),
      });
    });
  }

  unlinkTransaction(item: BudgetItemResponse): void {
    this.planningService.unlinkItem(item.id!).subscribe({
      next: updated => this.replaceItem(updated),
      error: () => this.snackBar.open('Erro. Tente novamente.', 'OK', { duration: 3000 }),
    });
  }

  deleteItem(item: BudgetItemResponse): void {
    this.planningService.deleteItem(item.id!).subscribe({
      next: () => this.items.update(list => list.filter(i => i.id !== item.id)),
      error: () => this.snackBar.open('Erro. Tente novamente.', 'OK', { duration: 3000 }),
    });
  }

  statusLabel(status: string | undefined): string {
    const labels: Record<string, string> = {
      PENDING: 'Pendente',
      REALIZED: 'Realizado',
      SKIPPED: 'Ignorado',
    };
    return labels[status ?? ''] ?? (status ?? '');
  }

  private replaceItem(updated: BudgetItemResponse): void {
    this.items.update(list => list.map(i => i.id === updated.id ? updated : i));
  }
}
