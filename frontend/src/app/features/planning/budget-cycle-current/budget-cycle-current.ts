import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { finalize } from 'rxjs/operators';

import { PlanningService } from '../planning.service';
import { BudgetCycleResponse, BudgetItemResponse, TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';
import { DEFAULT_SUMMARY } from './budget-cycle.utils';
import { BudgetItemFormComponent, BudgetItemFormResult } from '../budget-item-form/budget-item-form';
import { LinkTransactionDialogComponent, LinkTransactionDialogData } from '../link-transaction-dialog/link-transaction-dialog';

@Component({
  selector: 'app-budget-cycle-current',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe, RouterLink,
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
  readonly summary        = computed(() => this.cycle()?.summary ?? DEFAULT_SUMMARY);
  readonly unplannedItems = computed(() => this.cycle()?.unplannedTransactions ?? []);

  ngOnInit(): void {
    this.loadCurrentCycle();
  }

  loadCurrentCycle(): void {
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
    ref.afterClosed().subscribe((referenceMonth?: string) => {
      if (!referenceMonth) return;
      this.planningService.openCycle({ referenceMonth }).subscribe({
        next: c => {
          this.cycle.set(c);
          this.items.set(c.items ?? []);
          this.snackBar.open('Ciclo aberto com sucesso.', 'OK', { duration: 3000 });
        },
        error: () => this.snackBar.open('Erro ao abrir ciclo.', 'OK', { duration: 3000 }),
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
      data: {
        item,
        cycleId,
        startDate: this.cycle()!.startDate!,
        endDate: this.cycle()!.endDate!,
      } satisfies LinkTransactionDialogData,
    });
    ref.afterClosed().subscribe((transactionId?: string) => {
      if (!transactionId) return;
      this.planningService.linkItem(item.id!, { transactionId }).subscribe({
        next: () => this.loadCurrentCycle(),
        error: () => this.snackBar.open('Erro. Tente novamente.', 'OK', { duration: 3000 }),
      });
    });
  }

  unlinkTransaction(item: BudgetItemResponse): void {
    this.planningService.unlinkItem(item.id!).subscribe({
      next: () => this.loadCurrentCycle(),
      error: () => this.snackBar.open('Erro. Tente novamente.', 'OK', { duration: 3000 }),
    });
  }

  deleteItem(item: BudgetItemResponse): void {
    this.planningService.deleteItem(item.id!).subscribe({
      next: () => this.items.update(list => list.filter(i => i.id !== item.id)),
      error: () => this.snackBar.open('Erro. Tente novamente.', 'OK', { duration: 3000 }),
    });
  }

  linkFromUnplanned(tx: TransactionResponseDTO): void {
    const pendingItems = this.items().filter(
      i => i.type === tx.type && i.status === 'PENDING'
    );
    if (pendingItems.length === 0) {
      this.snackBar.open(
        'Nenhum item planejado pendente do mesmo tipo para vincular.',
        'OK',
        { duration: 3000 }
      );
      return;
    }
    // TODO: abrir LinkBudgetItemDialogComponent (criado na Task 7)
    // Por ora, apenas exibir snackbar informativo
    this.snackBar.open(
      `${pendingItems.length} item(s) disponível(is) para vincular. Dialog será adicionado na Task 7.`,
      'OK',
      { duration: 3000 }
    );
  }
}
