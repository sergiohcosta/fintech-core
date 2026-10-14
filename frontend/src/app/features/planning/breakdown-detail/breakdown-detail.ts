import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { finalize } from 'rxjs';

import { PlanningService } from '../planning.service';
import { BudgetCycleSummary } from '../../../core/api/fintechSaaSAPI.schemas';
import { DEFAULT_SUMMARY } from '../budget-cycle-current/budget-cycle.utils';
import { buildBreakdown, BreakdownCard, CardBreakdown } from '../breakdown/budget-cycle-breakdown';

// O detalhe por card usa os mesmos dados e cálculo do diálogo desktop; a rota dá espaço
// vertical para ler todas as categorias sem cobrir o ciclo no celular.
export function parseBreakdownCard(value: string | null): BreakdownCard | null {
  return value === 'income' || value === 'expense' || value === 'balance' || value === 'available'
    ? value
    : null;
}

@Component({
  selector: 'app-breakdown-detail',
  standalone: true,
  imports: [CommonModule, CurrencyPipe, RouterLink, MatButtonModule, MatIconModule, MatProgressSpinnerModule, MatSnackBarModule],
  templateUrl: './breakdown-detail.html',
  styleUrl: './breakdown-detail.scss',
})
export class BreakdownDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly planningService = inject(PlanningService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal(true);
  readonly breakdown = signal<CardBreakdown | null>(null);

  ngOnInit(): void {
    const card = parseBreakdownCard(this.route.snapshot.paramMap.get('id'));
    if (!card) {
      this.router.navigate(['/planning/current']);
      return;
    }

    this.planningService.getCurrentCycle()
      .pipe(finalize(() => this.loading.set(false)), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: cycle => {
          const summary: Required<BudgetCycleSummary> = {
            ...DEFAULT_SUMMARY,
            ...(cycle.summary ?? {}),
          };
          this.breakdown.set(buildBreakdown(card, {
            openingBalance: cycle.openingBalance ?? 0,
            summary,
            items: cycle.items ?? [],
            unplanned: cycle.unplannedTransactions ?? [],
          }));
        },
        error: () => {
          this.snackBar.open('Não foi possível carregar a composição do ciclo.', 'OK', { duration: 4000 });
          this.router.navigate(['/planning/current']);
        },
      });
  }
}
