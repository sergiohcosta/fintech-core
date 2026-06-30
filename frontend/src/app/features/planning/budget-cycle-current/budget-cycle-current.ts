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
import { finalize } from 'rxjs/operators';

import { PlanningService } from '../planning.service';
import { BudgetCycleResponse, BudgetCycleSummary, BudgetItemResponse, RecurrenceRuleCreateDTO, TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';
import { RecurringItemFormComponent } from '../recurring-item-form/recurring-item-form';
import { DEFAULT_SUMMARY, findDuplicateRecurring } from './budget-cycle.utils';
import { BudgetItemFormComponent, BudgetItemFormResult } from '../budget-item-form/budget-item-form';
import { LinkTransactionDialogComponent, LinkTransactionDialogData } from '../link-transaction-dialog/link-transaction-dialog';
import { LinkBudgetItemDialogComponent, LinkBudgetItemDialogData } from '../link-budget-item-dialog/link-budget-item-dialog';
import { ConfirmationDialogComponent, ConfirmationDialogData } from '../../../components/confirmation-dialog/confirmation-dialog';
import { BreakdownDialogComponent } from '../breakdown/breakdown-dialog';
import { buildBreakdown, BreakdownCard } from '../breakdown/budget-cycle-breakdown';

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

  ngOnInit(): void {
    this.loadCurrentCycle();
  }

  // Abre o modal de composição do card. A conta é montada por buildBreakdown a partir
  // dos dados que o componente já tem (sem chamada à API).
  openBreakdown(card: BreakdownCard): void {
    const c = this.cycle();
    if (!c) return;
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
