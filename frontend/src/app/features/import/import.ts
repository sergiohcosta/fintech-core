import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { forkJoin, of, switchMap } from 'rxjs';
import { catchError, finalize } from 'rxjs/operators';

import { ImportsService } from '../../core/api/imports/imports.service';
import { AccountsService } from '../../core/api/accounts/accounts.service';
import { CategoriesService } from '../../core/api/categories/categories.service';
import type {
  AccountResponse,
  ImportBatchResponseDTO,
  StagedTransactionResponseDTO,
} from '../../core/api/fintechSaaSAPI.schemas';
import {
  allPendingHaveAccount,
  buildCommitRequest,
  confidenceLevel,
  directionLabel,
  fieldConfidence,
  fieldValueAsString,
  flattenCategories,
  formatConfidence,
  type CategoryOption,
  type ReviewFieldKey,
} from './import-utils';

/** Linha editável da revisão — o que o usuário confere/corrige antes de lançar. */
interface ReviewRow {
  stagedId: string;
  status: string;
  requiresReview: boolean;
  overallConfidence: number | null;
  amount: string;
  transaction_date: string;
  description: string;
  direction: string;
  payment_method: string;
  accountId: string | null;
  categoryId: string | null;
  confidences: Record<ReviewFieldKey, number | null>;
}

/**
 * Fluxo de importação por imagem (Fase 1): upload → extração → revisão → commit.
 * Signals-first (Zoneless). O estado da tela deriva do batch: sem batch = upload; batch FAILED =
 * fallback manual; batch extraído = revisão. O badge de baixa confiança usa a confiança que o
 * backend calculou — a UI só exibe, nunca recalcula threshold.
 */
@Component({
  selector: 'app-import',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatProgressBarModule,
    MatTooltipModule,
    MatSnackBarModule,
  ],
  templateUrl: './import.html',
  styleUrl: './import.scss',
})
export class ImportComponent implements OnInit {
  private readonly imports = inject(ImportsService);
  private readonly accountsService = inject(AccountsService);
  private readonly categoriesService = inject(CategoriesService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);

  readonly selectedFile = signal<File | null>(null);
  readonly uploading = signal(false);
  readonly loadingStaged = signal(false);
  readonly committing = signal(false);
  readonly dragging = signal(false);

  readonly batch = signal<ImportBatchResponseDTO | null>(null);
  readonly rows = signal<ReviewRow[]>([]);
  readonly accounts = signal<AccountResponse[]>([]);
  readonly categoryOptions = signal<CategoryOption[]>([]);

  readonly fileName = computed(() => this.selectedFile()?.name ?? '');
  readonly batchId = computed(() => this.batch()?.id ?? '');

  // Estágio da tela derivado do batch — nenhuma flag de UI redundante.
  readonly stage = computed<'upload' | 'failed' | 'review'>(() => {
    const b = this.batch();
    if (!b) return 'upload';
    if (b.status === 'FAILED') return 'failed';
    return 'review';
  });

  readonly canConfirm = computed(() => allPendingHaveAccount(this.rows()) && !this.committing());

  ngOnInit(): void {
    this.accountsService.listAccounts().subscribe((list) => this.accounts.set(list));
    this.categoriesService
      .listCategories()
      .subscribe((tree) => this.categoryOptions.set(flattenCategories(tree)));
  }

  // --- Seleção de arquivo (input + drag-drop) ---

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFile.set(input.files && input.files.length > 0 ? input.files[0] : null);
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    const files = event.dataTransfer?.files;
    if (files && files.length > 0) {
      this.selectedFile.set(files[0]);
    }
  }

  clearFile(): void {
    this.selectedFile.set(null);
  }

  // --- Upload / extração ---

  upload(): void {
    const file = this.selectedFile();
    if (!file) {
      return;
    }
    this.uploading.set(true);
    this.imports
      .createImport({ file, importMode: 'NEW_TRANSACTIONS' })
      .pipe(finalize(() => this.uploading.set(false)))
      .subscribe({
        next: (batch) => {
          this.batch.set(batch);
          if (batch.status !== 'FAILED') {
            this.loadStaged(batch.id);
          }
        },
        error: (e) => this.snackBar.open(this.errorText(e, 'Falha ao extrair a imagem.'), 'OK', { duration: 6000 }),
      });
  }

  private loadStaged(batchId: string): void {
    this.loadingStaged.set(true);
    this.imports
      .listImportStaged(batchId)
      .pipe(finalize(() => this.loadingStaged.set(false)))
      .subscribe({
        next: (staged) => this.rows.set(staged.map((s) => this.toRow(s))),
        error: (e) => this.snackBar.open(this.errorText(e, 'Falha ao carregar os dados extraídos.'), 'OK', { duration: 6000 }),
      });
  }

  private toRow(s: StagedTransactionResponseDTO): ReviewRow {
    return {
      stagedId: s.id,
      status: s.status,
      requiresReview: s.requiresReview,
      overallConfidence: s.overallConfidence ?? null,
      amount: fieldValueAsString(s, 'amount'),
      transaction_date: fieldValueAsString(s, 'transaction_date'),
      description: fieldValueAsString(s, 'description'),
      direction: fieldValueAsString(s, 'direction') || 'debit',
      payment_method: fieldValueAsString(s, 'payment_method'),
      accountId: null,
      categoryId: null,
      confidences: {
        amount: fieldConfidence(s, 'amount'),
        transaction_date: fieldConfidence(s, 'transaction_date'),
        description: fieldConfidence(s, 'description'),
        direction: fieldConfidence(s, 'direction'),
        payment_method: fieldConfidence(s, 'payment_method'),
      },
    };
  }

  // --- Edição in-place (signals imutáveis) ---

  updateField(stagedId: string, field: keyof ReviewRow, value: string | null): void {
    this.rows.update((rows) =>
      rows.map((r) => (r.stagedId === stagedId ? { ...r, [field]: value } : r)),
    );
  }

  onInput(stagedId: string, field: keyof ReviewRow, event: Event): void {
    this.updateField(stagedId, field, (event.target as HTMLInputElement).value);
  }

  // --- Commit ---

  confirm(): void {
    const rows = this.rows();
    const pending = rows.filter((r) => r.status === 'PENDING' && r.accountId);
    if (pending.length === 0) {
      this.snackBar.open('Selecione a conta de cada lançamento antes de confirmar.', 'OK', { duration: 4000 });
      return;
    }
    const id = this.batchId();
    this.committing.set(true);

    // Persiste as edições (PATCH marca confiança 1.0) ANTES de lançar — o commit lê os valores
    // já gravados. forkJoin garante que todos os patches terminem antes do commit disparar.
    const patches = pending.map((r) =>
      this.imports.patchImportStaged(id, r.stagedId, { fields: this.patchFieldsOf(r) }),
    );

    forkJoin(patches)
      .pipe(
        switchMap(() => this.imports.commitImport(id, buildCommitRequest(rows))),
        catchError((e) => {
          this.snackBar.open(this.errorText(e, 'Falha ao lançar as transações.'), 'OK', { duration: 6000 });
          return of(null);
        }),
        finalize(() => this.committing.set(false)),
      )
      .subscribe((result) => {
        if (result) {
          this.snackBar.open('Transações lançadas com sucesso.', 'OK', { duration: 4000 });
          this.router.navigate(['/transactions']);
        }
      });
  }

  private patchFieldsOf(row: ReviewRow): Record<string, unknown> {
    const fields: Record<string, unknown> = {};
    const put = (key: string, value: string) => {
      if (value && value.trim()) {
        fields[key] = value.trim();
      }
    };
    put('amount', row.amount);
    put('transaction_date', row.transaction_date);
    put('description', row.description);
    put('direction', row.direction);
    put('payment_method', row.payment_method);
    return fields;
  }

  // --- Fallback / reset ---

  goToManual(): void {
    this.router.navigate(['/transactions/new']);
  }

  reset(): void {
    this.batch.set(null);
    this.rows.set([]);
    this.selectedFile.set(null);
  }

  // --- Helpers de template (delegam à lógica pura) ---

  confPct(row: ReviewRow, key: ReviewFieldKey): string {
    return formatConfidence(row.confidences[key]);
  }

  confClass(row: ReviewRow, key: ReviewFieldKey): 'low' | 'high' {
    return confidenceLevel(row.confidences[key]);
  }

  directionText(value: string): string {
    return directionLabel(value);
  }

  private errorText(error: unknown, fallback: string): string {
    const err = error as { error?: { message?: string } };
    return err?.error?.message ?? fallback;
  }
}
