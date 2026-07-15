import { Component, input, signal, computed, ChangeDetectionStrategy } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { TransactionResponseDTO } from '../../../../core/api/fintechSaaSAPI.schemas';
import { groupByRelativePeriod, RelativeGroup } from './grouped-list.utils';

function todayIso(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

@Component({
  selector: 'app-timeline-grouped-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CurrencyPipe, MatIconModule],
  templateUrl: './timeline-grouped-list.html',
  styleUrl: './timeline-grouped-list.scss',
})
export class TimelineGroupedListComponent {
  transactions = input.required<TransactionResponseDTO[]>();
  today = input<string>(todayIso());

  collapsed = signal<Set<string>>(new Set());
  groups = computed<RelativeGroup[]>(() => groupByRelativePeriod(this.transactions(), this.today()));

  toggle(bucket: string): void {
    this.collapsed.update(set => {
      const next = new Set(set);
      next.has(bucket) ? next.delete(bucket) : next.add(bucket);
      return next;
    });
  }

  isCollapsed(bucket: string): boolean {
    return this.collapsed().has(bucket);
  }
}
