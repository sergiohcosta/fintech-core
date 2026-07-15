import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule, CurrencyPipe } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { toSignal } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { PlanningService } from '../planning.service';
import { TransactionsService } from '../../../core/api/transactions/transactions.service';

interface CyclePreview {
  startDate: string;
  endDate: string;
  referenceMonth: string;
  suggestedOpeningBalance: number;
  projectedIncome: number;
  projectedExpense: number;
  recurringItems: Array<{ description?: string; expectedDate?: string; amount?: number; type?: string }>;
  installmentItems: Array<{ description?: string; accountName?: string; expectedDate?: string; amount?: number }>;
}

@Component({
  selector: 'app-budget-cycle-open-dialog',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatDialogModule, MatButtonModule, MatFormFieldModule,
    MatInputModule, MatIconModule, MatProgressSpinnerModule,
    MatTooltipModule, MatChipsModule, CurrencyPipe,
  ],
  templateUrl: './budget-cycle-open-dialog.html',
  styleUrl: './budget-cycle-open-dialog.scss',
})
export class BudgetCycleOpenDialogComponent implements OnInit {
  private readonly planningService     = inject(PlanningService);
  private readonly transactionsService = inject(TransactionsService);
  private readonly dialogRef           = inject(MatDialogRef<BudgetCycleOpenDialogComponent>);
  private readonly snackBar            = inject(MatSnackBar);

  readonly preview             = signal<CyclePreview | null>(null);
  readonly loadingPreview      = signal(true);
  readonly saving              = signal(false);
  readonly previewError        = signal<string | null>(null);
  readonly loadingTransactions = signal(false);
  readonly existingTxSummary   = signal<{ count: number; expense: number; income: number } | null>(null);

  readonly form = new FormGroup({
    openingBalance: new FormControl<number | null>(null, [Validators.required, Validators.min(0)]),
  });

  private readonly statusSignal = toSignal(this.form.statusChanges, { initialValue: this.form.status });
  readonly isInvalid = computed(() => this.statusSignal() === 'INVALID');

  // Reativo para recalcular projectedBalance ao editar o campo
  private readonly openingBalanceValue = toSignal(
    this.form.controls.openingBalance.valueChanges,
    { initialValue: this.form.controls.openingBalance.value },
  );

  readonly projectedBalance = computed(() => {
    const p = this.preview();
    if (!p) return null;
    return (this.openingBalanceValue() ?? 0) + (p.projectedIncome ?? 0) - (p.projectedExpense ?? 0);
  });

  // Formata "junho de 2026" a partir de startDate, sem depender de LOCALE_ID registrado
  readonly monthLabel = computed(() => {
    const start = this.preview()?.startDate;
    if (!start) return '';
    const d = new Date(start + 'T12:00:00');
    const label = new Intl.DateTimeFormat('pt-BR', { month: 'long', year: 'numeric' }).format(d);
    return label.charAt(0).toUpperCase() + label.slice(1);
  });

  ngOnInit(): void {
    const pad = (n: number) => String(n).padStart(2, '0');
    const fmt = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
    const now = new Date();
    const start = new Date(now.getFullYear(), now.getMonth() + 1, 1);
    const end   = new Date(now.getFullYear(), now.getMonth() + 2, 0);
    const refMonth = `${start.getFullYear()}-${pad(start.getMonth() + 1)}`;

    this.planningService.listRecurring().subscribe({
      next: recurring => {
        this.preview.set({
          startDate:              fmt(start),
          endDate:                fmt(end),
          referenceMonth:         refMonth,
          suggestedOpeningBalance: 0,
          projectedIncome:  recurring.filter(r => r.type === 'INCOME').reduce((s, r) => s + (r.baseAmount ?? 0), 0),
          projectedExpense: recurring.filter(r => r.type === 'EXPENSE').reduce((s, r) => s + (r.baseAmount ?? 0), 0),
          recurringItems: recurring.map(r => ({
            description: r.description,
            expectedDate: fmt(new Date(start.getFullYear(), start.getMonth(), this.dayFromRrule(r.rrule))),
            amount: r.baseAmount,
            type: r.type,
          })),
          installmentItems: [],
        });
        this.form.patchValue({ openingBalance: 0 });
        this.loadingPreview.set(false);
        this.loadExistingTransactions(fmt(start), fmt(end));
      },
      error: (err: HttpErrorResponse) => {
        const body = err.error as { message?: string } | null;
        this.previewError.set(body?.message ?? 'Erro ao carregar preview do ciclo.');
        this.loadingPreview.set(false);
      },
    });
  }

  private loadExistingTransactions(startDate: string, endDate: string): void {
    this.loadingTransactions.set(true);
    this.transactionsService.listTransactions({ startDate, endDate }).subscribe({
      next: txs => {
        const active = txs.filter(t => t.status !== 'CANCELLED');
        if (active.length > 0) {
          const expense = active.filter(t => t.type === 'EXPENSE').reduce((s, t) => s + t.amount, 0);
          const income  = active.filter(t => t.type === 'INCOME').reduce((s, t) => s + t.amount, 0);
          this.existingTxSummary.set({ count: active.length, expense, income });
        }
        this.loadingTransactions.set(false);
      },
      error: () => this.loadingTransactions.set(false), // silencioso — não bloqueia o dialog
    });
  }

  confirm(): void {
    const p = this.preview();
    if (!p || this.form.invalid) return;
    this.saving.set(true);

    this.planningService.openCycle({
      referenceMonth: p.referenceMonth,
    }).subscribe({
      next: () => {
        this.snackBar.open('Ciclo aberto com sucesso.', 'OK', { duration: 2000 });
        this.dialogRef.close(true);
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        const body = err.error as { message?: string } | null;
        this.snackBar.open(body?.message ?? 'Erro ao abrir ciclo.', 'OK', { duration: 3000 });
      },
    });
  }

  cancel(): void {
    this.dialogRef.close(false);
  }

  /** Extrai o dia do mês numérico da string RRULE. Ex: "FREQ=MONTHLY;BYMONTHDAY=15" → 15. */
  private dayFromRrule(rrule?: string | null): number {
    if (!rrule) return 1;
    const match = rrule.match(/BYMONTHDAY=(-?\d+)/);
    if (!match) return 1;
    const day = parseInt(match[1], 10);
    return day === -1 ? 28 : day; // -1 significa último dia; usa 28 como aproximação segura
  }
}
