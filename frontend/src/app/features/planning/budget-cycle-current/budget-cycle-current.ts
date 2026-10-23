import { Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { BreakpointObserver } from '@angular/cdk/layout';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { finalize } from 'rxjs/operators';

import { PlanningService } from '../planning.service';
import { BudgetCycleResponse, BudgetCycleSummary, BudgetItemResponse, RecurrenceRuleCreateDTO, TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';
import { RecurringItemFormComponent } from '../recurring-item-form/recurring-item-form';
import { DEFAULT_SUMMARY, findDuplicateRecurring } from './budget-cycle.utils';
import { BudgetItemFormComponent, BudgetItemFormResult } from '../budget-item-form/budget-item-form';
import { BudgetCycleOpenDialogComponent } from '../budget-cycle-open-dialog/budget-cycle-open-dialog';
import { LinkTransactionDialogComponent, LinkTransactionDialogData } from '../link-transaction-dialog/link-transaction-dialog';
import { LinkBudgetItemDialogComponent, LinkBudgetItemDialogData } from '../link-budget-item-dialog/link-budget-item-dialog';
import { ConfirmationDialogComponent, ConfirmationDialogData } from '../../../components/confirmation-dialog/confirmation-dialog';
import { BreakdownDialogComponent } from '../breakdown/breakdown-dialog';
import { buildBreakdown, BreakdownCard } from '../breakdown/budget-cycle-breakdown';

// O breakpoint muda apenas o destino da composição; os summaries e seus cálculos continuam
// vindo do mesmo ciclo, para que a versão móvel não replique nem altere regra financeira.
@Component({
  selector: 'app-budget-cycle-current',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe,
    MatButtonModule, MatCardModule, MatChipsModule, MatExpansionModule,
    MatIconModule, MatSnackBarModule, MatTableModule, MatTooltipModule,
    MatProgressBarModule, MatMenuModule, MatProgressSpinnerModule, MatDividerModule,
  ],
  templateUrl: './budget-cycle-current.html',
  styleUrl: './budget-cycle-current.scss',
})
export class BudgetCycleCurrentComponent implements OnInit {
  private readonly planningService = inject(PlanningService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly breakpointObserver = inject(BreakpointObserver);

  readonly isCompact = signal(false);
  readonly cycle = signal<BudgetCycleResponse | null>(null);
  readonly items = signal<BudgetItemResponse[]>([]);
  readonly loading = signal(true);
  readonly closing = signal(false);
  // Confirmação inline de exclusão: guarda o id do item em estado "confirmar?".
  // Como é um único signal, abrir a confirmação numa linha cancela a de outra.
  readonly confirmingDeleteId = signal<string | null>(null);

  readonly incomeItems  = computed(() => this.items().filter(i => i.type === 'INCOME'));
  readonly expenseItems = computed(() => this.items().filter(i => i.type === 'EXPENSE' && i.source !== 'INSTALLMENT'));
  readonly installmentItems = computed(() => this.items().filter(i => i.source === 'INSTALLMENT'));
  readonly summary = computed((): Required<BudgetCycleSummary> => ({
    ...DEFAULT_SUMMARY,
    ...(this.cycle()?.summary ?? {}),
  }));
  readonly unplannedItems = computed(() => this.cycle()?.unplannedTransactions ?? []);

  readonly cycleDaysTotal = computed(() => {
    const c = this.cycle();
    if (!c?.startDate || !c?.endDate) return 30;
    const start = new Date(c.startDate + 'T00:00:00');
    const end = new Date(c.endDate + 'T00:00:00');
    const diff = Math.ceil((end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24)) + 1;
    return diff > 0 ? diff : 30;
  });

  readonly daysRemaining = computed(() => {
    return this.summary().remainingDays ?? 0;
  });

  readonly daysElapsed = computed(() => {
    const total = this.cycleDaysTotal();
    const remaining = this.daysRemaining();
    const elapsed = total - remaining;
    return Math.max(0, Math.min(total, elapsed));
  });

  readonly timeProgress = computed(() => {
    const total = this.cycleDaysTotal();
    if (total <= 0) return 0;
    return Math.min(100, Math.max(0, (this.daysElapsed() / total) * 100));
  });

  readonly incomeProgress = computed(() => {
    const planned = this.summary().plannedIncome ?? 0;
    const realized = this.summary().realizedIncome ?? 0;
    if (planned <= 0) return realized > 0 ? 100 : 0;
    return Math.min(100, (realized / planned) * 100);
  });

  readonly expenseProgress = computed(() => {
    const planned = this.summary().plannedExpense ?? 0;
    const realized = this.summary().realizedExpense ?? 0;
    if (planned <= 0) return realized > 0 ? 100 : 0;
    return Math.min(100, (realized / planned) * 100);
  });

  statusLabel(status?: string | null): string {
    switch (status) {
      case 'PENDING': return 'Aguardando';
      case 'REALIZED': return 'Realizado';
      case 'SKIPPED': return 'Ignorado';
      case 'INSTALLMENT': return 'Parcela';
      default: return status ?? 'Pendente';
    }
  }

  statusClass(status?: string | null): string {
    switch (status) {
      case 'REALIZED': return 'status-realized';
      case 'PENDING': return 'status-pending';
      case 'SKIPPED': return 'status-skipped';
      default: return '';
    }
  }

  constructor() {
    // O modal continua no desktop; em compact a mesma composição vira uma página navegável,
    // preservando o contexto de voltar ao ciclo atual.
    this.breakpointObserver.observe('(max-width: 719.98px)')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(({ matches }) => this.isCompact.set(matches));
  }

  ngOnInit(): void {
    this.loadCurrentCycle();
  }

  // Abre o modal de composição do card. A conta é montada por buildBreakdown a partir
  // dos dados que o componente já tem (sem chamada à API).
  openBreakdown(card: BreakdownCard): void {
    const c = this.cycle();
    if (!c) return;
    if (this.isCompact()) {
      this.router.navigate(['/planning', 'breakdown', card]);
      return;
    }
    this.dialog.open(BreakdownDialogComponent, {
      width: '480px',
      data: buildBreakdown(card, {
        openingBalance: c.openingBalance ?? 0,
        summary: this.summary(),
        items: this.items(),
        unplanned: this.unplannedItems(),
      }),
    });
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

  // Refresh silencioso após mutações: re-busca o ciclo (items + não planejados + summary,
  // todos consistentes pelo backend) sem ligar o loading — os cards atualizam sem flash.
  private refreshCycle(): void {
    this.planningService.getCurrentCycle().subscribe({
      next: c => { this.cycle.set(c); this.items.set(c.items ?? []); },
      error: () => this.snackBar.open('Erro ao atualizar o resumo.', 'OK', { duration: 3000 }),
    });
  }

  openCycle(): void {
    const ref = this.dialog.open(BudgetCycleOpenDialogComponent, {
      width: '640px',
      maxWidth: '95vw',
    });
    ref.afterClosed().subscribe((opened?: boolean) => {
      if (opened) {
        this.loadCurrentCycle();
      }
    });
  }

  closeCycle(): void {
    const id = this.cycle()?.id;
    if (!id) return;
    const ref = this.dialog.open(ConfirmationDialogComponent, {
      data: {
        title: 'Fechar ciclo em andamento',
        message: 'Este ciclo ainda está em andamento. Fechar agora encerrará o planejamento antes do fim do período. Deseja continuar?',
        confirmText: 'Fechar ciclo',
        cancelText: 'Cancelar',
      } satisfies ConfirmationDialogData,
    });
    ref.afterClosed().subscribe((confirmed: boolean) => {
      if (!confirmed) return;
      this.closing.set(true);
      this.planningService.closeCycle(id, true)
        .pipe(finalize(() => this.closing.set(false)))
        .subscribe({
          next: c => {
            this.cycle.set(c);
            this.snackBar.open('Ciclo fechado.', 'OK', { duration: 3000 });
          },
          error: () => this.snackBar.open('Erro ao fechar ciclo.', 'OK', { duration: 3000 }),
        });
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
        next: () => {
          this.refreshCycle();
          this.snackBar.open('Item adicionado.', 'OK', { duration: 2000 });
        },
        error: () => this.snackBar.open('Erro. Tente novamente.', 'OK', { duration: 3000 }),
      });
    });
  }

  // Cria uma regra de recorrência a partir de um item do ciclo.
  // Abre o form de criação (sem prefill — o formulário novo exige campos como accountId e rrule
  // que não estão disponíveis em BudgetItemResponse). O usuário preenche os campos obrigatórios.
  // Não altera o ciclo atual — a regra só afeta ciclos futuros — então sem refresh.
  makeRecurring(item: BudgetItemResponse): void {
    const ref = this.dialog.open(RecurringItemFormComponent, {
      width: '500px',
      data: null,
    });
    ref.afterClosed().subscribe((result?: RecurrenceRuleCreateDTO) => {
      if (!result) return;
      // Guard de deduplicação: avisa se já existe recorrente ativo com mesma descrição/tipo.
      this.planningService.listRecurring().subscribe(existing => {
        const dup = findDuplicateRecurring(existing, result.description, result.type);
        if (!dup) {
          this.persistRecurring(result);
          return;
        }
        const confirmRef = this.dialog.open(ConfirmationDialogComponent, {
          data: {
            title: 'Recorrente já existe',
            message: `Já existe um item recorrente "${dup.description}" do mesmo tipo. Criar mesmo assim?`,
            confirmText: 'Criar mesmo assim',
            cancelText: 'Cancelar',
          } satisfies ConfirmationDialogData,
        });
        confirmRef.afterClosed().subscribe((confirmed: boolean) => {
          if (confirmed) this.persistRecurring(result);
        });
      });
    });
    // Suprime aviso de variável não usada; item é mantido na assinatura para compatibilidade de template.
    void item;
  }

  private persistRecurring(result: RecurrenceRuleCreateDTO): void {
    this.planningService.createRecurring(result).subscribe({
      next: () => this.snackBar.open('Regra de recorrência criada.', 'OK', { duration: 2000 }),
      error: () => this.snackBar.open('Erro ao criar recorrência.', 'OK', { duration: 3000 }),
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
        next: () => this.refreshCycle(),
        error: () => this.snackBar.open('Erro. Tente novamente.', 'OK', { duration: 3000 }),
      });
    });
  }

  unlinkTransaction(item: BudgetItemResponse): void {
    this.planningService.unlinkItem(item.id!).subscribe({
      next: () => this.refreshCycle(),
      error: () => this.snackBar.open('Erro. Tente novamente.', 'OK', { duration: 3000 }),
    });
  }

  deleteItem(item: BudgetItemResponse): void {
    this.planningService.deleteItem(item.id!).subscribe({
      next: () => {
        this.confirmingDeleteId.set(null);
        this.refreshCycle();
      },
      error: () => this.snackBar.open('Erro. Tente novamente.', 'OK', { duration: 3000 }),
    });
  }

  // Cria um item planejado a partir de uma transação não planejada e já o vincula a ela
  // (vira realizado e sai da lista de não planejados). Form prefilled com os dados da transação.
  createPlannedFromUnplanned(tx: TransactionResponseDTO): void {
    const cycleId = this.cycle()?.id;
    if (!cycleId) return;
    const ref = this.dialog.open(BudgetItemFormComponent, {
      width: '500px',
      data: {
        cycleId,
        prefill: {
          description: tx.description,
          amount: tx.amount,
          type: tx.type,
          expectedDate: tx.date ? new Date(tx.date + 'T00:00:00') : undefined,
        },
      },
    });
    ref.afterClosed().subscribe((result?: BudgetItemFormResult) => {
      if (!result) return;
      this.planningService.createItem(cycleId, result).subscribe({
        next: item => {
          this.planningService.linkItem(item.id!, { transactionId: tx.id! }).subscribe({
            next: () => {
              this.refreshCycle();
              this.snackBar.open('Item planejado criado e vinculado.', 'OK', { duration: 2000 });
            },
            error: () => this.snackBar.open('Item criado, mas falha ao vincular.', 'OK', { duration: 3000 }),
          });
        },
        error: () => this.snackBar.open('Erro ao criar item.', 'OK', { duration: 3000 }),
      });
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
    const ref = this.dialog.open(LinkBudgetItemDialogComponent, {
      width: '600px',
      data: { transaction: tx, pendingItems } satisfies LinkBudgetItemDialogData,
    });
    ref.afterClosed().subscribe((itemId?: string) => {
      if (!itemId) return;
      this.planningService.linkItem(itemId, { transactionId: tx.id! }).subscribe({
        next: () => {
          this.refreshCycle();
          this.snackBar.open('Vinculado com sucesso.', 'OK', { duration: 2000 });
        },
        error: () => this.snackBar.open('Erro. Tente novamente.', 'OK', { duration: 3000 }),
      });
    });
  }
}
