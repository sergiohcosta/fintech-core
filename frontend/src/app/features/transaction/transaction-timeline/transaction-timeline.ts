// transaction-timeline.ts
import { Component, inject, signal, computed, effect, untracked, ChangeDetectionStrategy } from '@angular/core';
import { Router } from '@angular/router';
import { MatTabsModule } from '@angular/material/tabs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { TransactionsService } from '../../../core/api/transactions/transactions.service';
import { TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';
import { TimelineCalendarComponent } from './timeline-calendar/timeline-calendar';
import { TimelineGroupedListComponent } from './timeline-grouped-list/timeline-grouped-list';
import { TimelineHorizontalComponent } from './timeline-horizontal/timeline-horizontal';
import {
  TimelineFilters, TimelineViewMode, loadTimelineFilters, saveTimelineFilters,
} from './transaction-timeline.filters';

const VIEW_MODES: TimelineViewMode[] = ['calendar', 'grouped', 'horizontal'];

@Component({
  selector: 'app-transaction-timeline',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatTabsModule, MatButtonModule, MatIconModule,
    TimelineCalendarComponent, TimelineGroupedListComponent, TimelineHorizontalComponent,
  ],
  templateUrl: './transaction-timeline.html',
  styleUrl: './transaction-timeline.scss',
})
export class TransactionTimelineComponent {
  private service = inject(TransactionsService);
  private router = inject(Router);
  private snackBar = inject(MatSnackBar);

  filters = signal<TimelineFilters>(loadTimelineFilters());
  transactions = signal<TransactionResponseDTO[]>([]);

  monthAnchor = computed(() => this.filters().startDate ?? this.firstOfCurrentMonth());
  selectedTab = computed(() => Math.max(0, VIEW_MODES.indexOf(this.filters().viewMode)));

  // Régua da timeline horizontal: usa o período filtrado; sem filtro, cobre o mês inteiro
  // do anchor (1º ao último dia) — evita régua degenerada de um único dia.
  rangeStart = computed(() => this.filters().startDate ?? this.monthAnchor());
  rangeEnd = computed(() => {
    const end = this.filters().endDate;
    if (end) return end;
    const [y, m] = this.monthAnchor().split('-').map(Number);
    const lastDay = new Date(y, m, 0).getDate();
    return `${y}-${String(m).padStart(2, '0')}-${String(lastDay).padStart(2, '0')}`;
  });

  constructor() {
    // Recarrega sempre que mudam os campos que o backend conhece. description é client-side,
    // então é deliberadamente lida fora do effect para não disparar fetch.
    effect(() => {
      const f = this.filters();
      const serverKey = JSON.stringify({
        accountIds: f.accountIds, statuses: f.statuses, types: f.types,
        startDate: f.startDate, endDate: f.endDate,
      });
      untracked(() => this.loadTransactions(f, serverKey));
    });
  }

  private lastServerKey = '';
  private loadTransactions(f: TimelineFilters, serverKey: string): void {
    if (serverKey === this.lastServerKey) return; // evita refetch quando só viewMode/description mudaram
    this.lastServerKey = serverKey;
    this.service.listTransactions({
      accountIds: f.accountIds.length > 0 ? f.accountIds : undefined,
      status: f.statuses[0],
      type: f.types.find((t): t is 'INCOME' | 'EXPENSE' => t === 'INCOME' || t === 'EXPENSE'),
      startDate: f.startDate ?? undefined,
      endDate: f.endDate ?? undefined,
    }).subscribe({
      next: data => this.transactions.set(data),
      error: () => this.snackBar.open('Erro ao carregar transações.', 'Fechar', { duration: 5000 }),
    });
  }

  // Lista visível: aplica o filtro de descrição (client-side) sobre o que veio do backend.
  visibleTransactions = computed<TransactionResponseDTO[]>(() => {
    const desc = this.filters().description?.toLowerCase().trim();
    const txs = this.transactions();
    if (!desc) return txs;
    return txs.filter(t => t.description?.toLowerCase().includes(desc));
  });

  onTabChange(index: number): void {
    const mode = VIEW_MODES[index] ?? 'calendar';
    this.setFilters({ ...this.filters(), viewMode: mode });
  }

  onMonthChange(anchor: string): void {
    const [y, m] = anchor.split('-').map(Number);
    const lastDay = new Date(y, m, 0).getDate();
    const pad = (n: number) => String(n).padStart(2, '0');
    this.setFilters({ ...this.filters(), startDate: `${y}-${pad(m)}-01`, endDate: `${y}-${pad(m)}-${pad(lastDay)}` });
  }

  private setFilters(f: TimelineFilters): void {
    this.filters.set(f);
    saveTimelineFilters(f);
  }

  goToTransactionList(): void {
    const f = this.filters();
    const params: Record<string, string> = {};
    if (f.accountIds.length) params['accountIds'] = f.accountIds.join(',');
    if (f.statuses.length) params['status'] = f.statuses[0];
    if (f.types.length) params['type'] = f.types[0];
    if (f.startDate) params['startDate'] = f.startDate;
    if (f.endDate) params['endDate'] = f.endDate;
    if (f.description) params['description'] = f.description;
    this.router.navigate(['/transactions'], { queryParams: params });
  }

  private firstOfCurrentMonth(): string {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
  }
}
