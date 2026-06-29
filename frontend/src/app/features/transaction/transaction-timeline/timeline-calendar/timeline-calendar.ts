import { Component, input, output, signal, computed, ChangeDetectionStrategy } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { TransactionResponseDTO } from '../../../../core/api/fintechSaaSAPI.schemas';
import { buildMonthGrid, formatMonthLabel, DayCell } from './calendar-utils';

@Component({
  selector: 'app-timeline-calendar',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CurrencyPipe, MatIconModule, MatButtonModule, MatTooltipModule],
  templateUrl: './timeline-calendar.html',
  styleUrl: './timeline-calendar.scss',
})
export class TimelineCalendarComponent {
  transactions = input.required<TransactionResponseDTO[]>();
  monthAnchor = input.required<string>();
  monthChange = output<string>();

  expandedDay = signal<string | null>(null);

  readonly weekdayLabels = ['Dom', 'Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb'];
  grid = computed<DayCell[]>(() => buildMonthGrid(this.monthAnchor(), this.transactions()));
  monthLabel = computed(() => formatMonthLabel(this.monthAnchor()));

  expandedTransactions = computed<TransactionResponseDTO[]>(() => {
    const day = this.expandedDay();
    if (!day) return [];
    return this.grid().find(c => c.date === day)?.transactions ?? [];
  });

  toggleDay(cell: DayCell): void {
    if (!cell.date || cell.transactions.length === 0) return;
    this.expandedDay.update(d => (d === cell.date ? null : cell.date));
  }

  prevMonth(): void {
    this.monthChange.emit(this.shiftMonth(-1));
  }
  nextMonth(): void {
    this.monthChange.emit(this.shiftMonth(1));
  }
  private shiftMonth(delta: number): string {
    const [y, m] = this.monthAnchor().split('-').map(Number);
    const d = new Date(y, m - 1 + delta, 1);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
  }
}
