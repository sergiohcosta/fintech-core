import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { finalize } from 'rxjs/operators';

import { ImportsService } from '../../../core/api/imports/imports.service';
import type { ImportBatchResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';
import { batchStatusClass, batchStatusLabel, sourceTypeLabel } from './import-history.utils';

/** Histórico de importações do tenant — lista mais recente primeiro, sem paginação (volume
 *  baixo por família). Cada linha leva pra `/import/:id`, que reusa o mesmo fluxo de revisão. */
@Component({
  selector: 'app-import-history',
  standalone: true,
  imports: [
    CommonModule,
    DatePipe,
    RouterLink,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
  ],
  templateUrl: './import-history.html',
  styleUrl: './import-history.scss',
})
export class ImportHistory implements OnInit {
  private readonly imports = inject(ImportsService);

  readonly batches = signal<ImportBatchResponseDTO[]>([]);
  readonly loading = signal(false);

  readonly displayedColumns = ['sourceFilename', 'sourceType', 'status', 'createdAt', 'actions'];

  ngOnInit(): void {
    this.loading.set(true);
    this.imports
      .listImports()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe((batches) => this.batches.set(batches));
  }

  statusLabel(status: string): string {
    return batchStatusLabel(status);
  }

  statusClass(status: string): string {
    return batchStatusClass(status);
  }

  sourceLabel(sourceType: string): string {
    return sourceTypeLabel(sourceType);
  }
}
