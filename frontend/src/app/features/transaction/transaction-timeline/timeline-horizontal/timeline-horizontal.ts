import { Component, input, signal, computed, ChangeDetectionStrategy } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { TransactionResponseDTO } from '../../../../core/api/fintechSaaSAPI.schemas';
import { resolveCollisions, PositionedMarker } from './horizontal-utils';

@Component({
  selector: 'app-timeline-horizontal',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CurrencyPipe, MatIconModule],
  templateUrl: './timeline-horizontal.html',
  styleUrl: './timeline-horizontal.scss',
})
export class TimelineHorizontalComponent {
  transactions = input.required<TransactionResponseDTO[]>();
  rangeStart = input.required<string>();
  rangeEnd = input.required<string>();

  // Largura virtual da faixa em px. ~32px por dia dá respiro visual; o scroll horizontal
  // do container cobre o que exceder a viewport.
  readonly trackWidth = 1200;
  selectedMarker = signal<string | null>(null);

  markers = computed<PositionedMarker[]>(() =>
    resolveCollisions(this.transactions(), this.rangeStart(), this.rangeEnd(), this.trackWidth),
  );

  selected = computed<PositionedMarker | null>(() => {
    const d = this.selectedMarker();
    return d ? this.markers().find(m => m.date === d) ?? null : null;
  });

  select(marker: PositionedMarker): void {
    this.selectedMarker.update(d => (d === marker.date ? null : marker.date));
  }
}
