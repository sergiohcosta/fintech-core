import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { SelectionModel } from '@angular/cdk/collections';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatPaginatorModule, type PageEvent } from '@angular/material/paginator';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
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
  parseAmountInput,
  formatLocalDate,
} from '../transaction/transaction-form/transaction-form.utils';
import {
  anyRowReadyToCommit,
  applyBulkField,
  buildCommitRequest,
  conflictMessage,
  confidenceLevel,
  fieldConfidence,
  fieldValueAsString,
  flattenCategories,
  formatAmountCurrency,
  formatAmountDisplay,
  formatConfidence,
  formatDatePtBr,
  isReadyToCommit,
  parseDuplicateConflict,
  type CategoryOption,
  type DuplicateConflict,
  type ReviewFieldKey,
} from './import-utils';

/** Linha editável da revisão — o que o usuário confere/corrige antes de lançar. */
interface ReviewRow {
  stagedId: string;
  status: string;
  requiresReview: boolean;
  overallConfidence: number | null;
  amount: string;
  /** Espelho de `amount` formatado pt-BR para o input de edição (padrão de `transaction-form.ts`):
   *  digitar é livre, o parse pt-BR só acontece no blur — `amount` (canônico) é o que vai no PATCH. */
  amountDisplay: string;
  transaction_date: string;
  description: string;
  direction: string;
  payment_method: string;
  accountId: string | null;
  categoryId: string | null;
  duplicateCandidateOf: string | null;
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
    MatTableModule,
    MatCheckboxModule,
    MatPaginatorModule,
    MatDatepickerModule,
    MatNativeDateModule,
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
  readonly discarding = signal(false);
  readonly dragging = signal(false);

  readonly batch = signal<ImportBatchResponseDTO | null>(null);
  readonly rows = signal<ReviewRow[]>([]);
  readonly accounts = signal<AccountResponse[]>([]);
  readonly categoryOptions = signal<CategoryOption[]>([]);
  // 409 (mesmo arquivo já importado) — nenhum batch é criado; a tela oferece "importar mesmo assim".
  readonly duplicateConflict = signal<DuplicateConflict | null>(null);

  readonly fileName = computed(() => this.selectedFile()?.name ?? '');
  readonly batchId = computed(() => this.batch()?.id ?? '');

  // Estágio da tela derivado do batch — nenhuma flag de UI redundante.
  readonly stage = computed<'upload' | 'failed' | 'review'>(() => {
    const b = this.batch();
    if (!b) return 'upload';
    if (b.status === 'FAILED') return 'failed';
    return 'review';
  });

  // Motivo da recusa vindo do backend (#193). Fallback local só cobre batch antigo (pré-V25),
  // que não tem motivo gravado — o texto certo é sempre o do backend, que conhece a causa.
  readonly failureReason = computed(
    () =>
      this.batch()?.failureReason ??
      'A imagem pode estar ilegível ou o serviço de extração indisponível.',
  );

  readonly canConfirm = computed(
    () => anyRowReadyToCommit(this.rows()) && !this.committing() && !this.discarding(),
  );

  // --- Tabela: seleção, paginação e expansão (Fase 2 metade B) ---

  /** Colunas da tabela compacta. A edição fina (valor/data/descrição/tipo) vive na linha expandida. */
  readonly displayedColumns = [
    'select',
    'flags',
    'amount',
    'transaction_date',
    'description',
    'direction',
    'account',
    'category',
    'actions',
  ];

  /** Menor opção de página — abaixo disso o paginador não tem o que paginar e fica escondido. */
  static readonly MIN_PAGE_SIZE = 10;
  readonly pageSizeOptions = [10, 25, 50, 100];

  /**
   * `SelectionModel` do CDK guarda a seleção por `stagedId` (não por índice) — é o que permite
   * a seleção sobreviver à troca de página, já que o id não muda quando a fatia visível muda.
   * O signal `selectedIds` é o espelho lido pelo template: SelectionModel é um objeto mutável e
   * não notifica o change detection Zoneless sozinho; a ponte é a subscrição em `changed`.
   */
  private readonly selection = new SelectionModel<string>(true, []);
  readonly selectedIds = signal<readonly string[]>([]);

  readonly pageIndex = signal(0);
  readonly pageSize = signal(25);
  readonly expandedId = signal<string | null>(null);

  /** Fatia visível da página atual — paginação é 100% client-side sobre o array já carregado. */
  readonly pagedRows = computed(() => {
    const start = this.pageIndex() * this.pageSize();
    return this.rows().slice(start, start + this.pageSize());
  });

  readonly showPaginator = computed(() => this.rows().length > ImportComponent.MIN_PAGE_SIZE);
  readonly hasSelection = computed(() => this.selectedIds().length > 0);
  readonly selectedCount = computed(() => this.selectedIds().length);

  // Contagem explícita antes de confirmar: com o gate relaxado (§2f da spec), "confirmar" pode
  // lançar só parte das linhas — o usuário precisa ver quantas de quantas vão de fato.
  readonly pendingCount = computed(() => this.rows().filter((r) => r.status === 'PENDING').length);
  readonly readyCount = computed(() => this.rows().filter(isReadyToCommit).length);

  /** Linha com conta escolhida (o usuário já decidiu que quer lançar) mas valor/data inválidos —
   *  não vai pro commit ainda; o ícone na coluna de flags avisa por que ela ficou de fora da
   *  contagem de "prontas" sem o usuário precisar tentar confirmar pra descobrir. */
  isBlockedFromCommit(row: ReviewRow): boolean {
    return row.status === 'PENDING' && !!row.accountId && !isReadyToCommit(row);
  }

  constructor() {
    this.selection.changed
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.selectedIds.set(this.selection.selected.slice()));
  }

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
    this.duplicateConflict.set(null);
  }

  // --- Upload / extração ---

  upload(force = false): void {
    const file = this.selectedFile();
    if (!file) {
      return;
    }
    this.uploading.set(true);
    this.duplicateConflict.set(null);
    this.imports
      .createImport({ file, importMode: 'NEW_TRANSACTIONS' }, { force })
      .pipe(finalize(() => this.uploading.set(false)))
      .subscribe({
        next: (batch) => {
          this.batch.set(batch);
          if (batch.status !== 'FAILED') {
            this.loadStaged(batch.id);
          }
        },
        error: (e) => {
          // 409 (mesmo arquivo já importado) tem tratamento PRÓPRIO — não é um erro genérico de
          // snackbar, é uma decisão do usuário (cancelar ou reimportar mesmo assim).
          const conflict = parseDuplicateConflict(e);
          if (conflict) {
            this.duplicateConflict.set(conflict);
            return;
          }
          this.snackBar.open(this.errorText(e, 'Falha ao extrair o arquivo.'), 'OK', {
            duration: 6000,
          });
        },
      });
  }

  /** Usuário decidiu reimportar mesmo com o arquivo já tendo sido importado antes. */
  importAnyway(): void {
    this.upload(true);
  }

  dismissConflict(): void {
    this.duplicateConflict.set(null);
  }

  conflictText(conflict: DuplicateConflict): string {
    return conflictMessage(conflict);
  }

  private loadStaged(batchId: string): void {
    this.loadingStaged.set(true);
    this.imports
      .listImportStaged(batchId)
      .pipe(finalize(() => this.loadingStaged.set(false)))
      .subscribe({
        // Linhas descartadas somem da revisão: descartar é decisão tomada, não estado a revisar.
        next: (staged) => {
          this.rows.set(staged.filter((s) => s.status !== 'DISCARDED').map((s) => this.toRow(s)));
          this.selection.clear();
          this.pageIndex.set(0);
          this.expandedId.set(null);
        },
        error: (e) =>
          this.snackBar.open(this.errorText(e, 'Falha ao carregar os dados extraídos.'), 'OK', {
            duration: 6000,
          }),
      });
  }

  private toRow(s: StagedTransactionResponseDTO): ReviewRow {
    return {
      stagedId: s.id,
      status: s.status,
      requiresReview: s.requiresReview,
      overallConfidence: s.overallConfidence ?? null,
      amount: fieldValueAsString(s, 'amount'),
      amountDisplay: formatAmountDisplay(fieldValueAsString(s, 'amount')),
      transaction_date: fieldValueAsString(s, 'transaction_date'),
      description: fieldValueAsString(s, 'description'),
      direction: fieldValueAsString(s, 'direction') || 'debit',
      payment_method: fieldValueAsString(s, 'payment_method'),
      accountId: null,
      categoryId: null,
      duplicateCandidateOf: s.duplicateCandidateOf ?? null,
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

  // --- Edição de valor (máscara pt-BR, mesmo padrão de transaction-form.ts) ---

  /** Digitação livre: só espelha o texto exibido, sem parsear ainda (evita brigar com o usuário
   *  no meio da digitação, ex. vírgula sendo trocada por ponto a cada tecla). */
  onAmountInputRow(stagedId: string, event: Event): void {
    const raw = (event.target as HTMLInputElement).value;
    this.rows.update((rows) =>
      rows.map((r) => (r.stagedId === stagedId ? { ...r, amountDisplay: raw } : r)),
    );
  }

  /** No blur, interpreta o texto digitado (pt-BR ou ponto-decimal) e grava o valor CANÔNICO
   *  (ponto-decimal) em `amount` — é ele que vai no PATCH; `amountDisplay` só formata pra tela. */
  onAmountBlurRow(stagedId: string, event: Event): void {
    const raw = (event.target as HTMLInputElement).value;
    const parsed = parseAmountInput(raw);
    this.rows.update((rows) =>
      rows.map((r) => {
        if (r.stagedId !== stagedId) {
          return r;
        }
        if (parsed === null) {
          return { ...r, amount: '', amountDisplay: '' };
        }
        const canonical = String(parsed);
        return { ...r, amount: canonical, amountDisplay: formatAmountDisplay(canonical) };
      }),
    );
  }

  /** No foco, troca a formatação pt-BR (ponto de milhar) pelo texto cru mais fácil de editar. */
  onAmountFocusRow(stagedId: string, event: Event): void {
    const row = this.rows().find((r) => r.stagedId === stagedId);
    const input = event.target as HTMLInputElement;
    input.value = row?.amount ? row.amount.replace('.', ',') : '';
  }

  // --- Edição de data (mat-datepicker; locale pt-BR já é global em app.config.ts) ---

  dateValue(row: ReviewRow): Date | null {
    if (!row.transaction_date) {
      return null;
    }
    const d = new Date(`${row.transaction_date}T00:00:00`);
    return Number.isNaN(d.getTime()) ? null : d;
  }

  onDateChange(stagedId: string, date: Date | null): void {
    this.updateField(stagedId, 'transaction_date', date ? formatLocalDate(date) : '');
  }

  // --- Helpers de exibição pt-BR (coluna compacta, somente leitura) ---

  amountText(row: ReviewRow): string {
    return formatAmountCurrency(row.amount);
  }

  dateText(row: ReviewRow): string {
    return formatDatePtBr(row.transaction_date);
  }

  // --- Seleção ---

  isSelected(stagedId: string): boolean {
    // Lê o SIGNAL (e não `selection.isSelected`) de propósito: assim o template reavalia sozinho
    // quando a seleção muda, sem depender de zone.js.
    return this.selectedIds().includes(stagedId);
  }

  toggleRow(stagedId: string): void {
    this.selection.toggle(stagedId);
  }

  /** Todas as linhas da página atual estão selecionadas? (estado do checkbox do cabeçalho) */
  allOnPageSelected(): boolean {
    const page = this.pagedRows();
    return page.length > 0 && page.every((r) => this.isSelected(r.stagedId));
  }

  /** Alguma (mas não todas) selecionada na página → checkbox do cabeçalho indeterminado. */
  someOnPageSelected(): boolean {
    const page = this.pagedRows();
    return page.some((r) => this.isSelected(r.stagedId)) && !this.allOnPageSelected();
  }

  /** "Selecionar todas" age só sobre a PÁGINA — nunca sobre linhas que o usuário não está vendo. */
  toggleAllOnPage(): void {
    const ids = this.pagedRows().map((r) => r.stagedId);
    if (this.allOnPageSelected()) {
      this.selection.deselect(...ids);
    } else {
      this.selection.select(...ids);
    }
  }

  clearSelection(): void {
    this.selection.clear();
  }

  /** Seleciona TODAS as linhas do lote, não só a página atual — é o que permite "aplicar a mesma
   *  conta a todas as transações" com um clique, mesmo com o resultado espalhado em várias
   *  páginas da tabela (a paginação aqui é só client-side, sobre o array já carregado). */
  selectAllRows(): void {
    this.selection.select(...this.rows().map((r) => r.stagedId));
  }

  // --- Paginação (client-side) ---

  onPage(event: PageEvent): void {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
    // A seleção NÃO é limpa ao paginar: ela é keyed por stagedId, então continua coerente com
    // linhas fora da página. Só a expansão fecha (pertence a uma linha que saiu da tela).
    this.expandedId.set(null);
  }

  // --- Expansão da linha (edição fina) ---

  toggleExpanded(stagedId: string): void {
    this.expandedId.update((current) => (current === stagedId ? null : stagedId));
  }

  isExpanded(stagedId: string): boolean {
    return this.expandedId() === stagedId;
  }

  // --- Edição em massa (local, sem API — §2d da spec) ---

  applyAccountToSelected(accountId: string): void {
    if (!accountId) {
      return;
    }
    this.rows.update((rows) => applyBulkField(rows, this.selectedIds(), 'accountId', accountId));
  }

  applyCategoryToSelected(categoryId: string | null): void {
    this.rows.update((rows) => applyBulkField(rows, this.selectedIds(), 'categoryId', categoryId));
  }

  applyDirectionToSelected(direction: string): void {
    this.rows.update((rows) => applyBulkField(rows, this.selectedIds(), 'direction', direction));
  }

  // --- Descarte (PENDING → DISCARDED no backend, linha sai da view) ---

  discardRow(stagedId: string): void {
    this.discardMany([stagedId]);
  }

  discardSelected(): void {
    this.discardMany(this.selectedIds());
  }

  /**
   * Descarte em lote reusa o endpoint individual via `forkJoin` — mesmo padrão do `confirm()`.
   * O volume aqui é de dezenas, não milhares: reusar rota já testada pesa mais que economizar
   * N chamadas HTTP (§2e da spec).
   */
  private discardMany(ids: readonly string[]): void {
    if (ids.length === 0 || this.discarding()) {
      return;
    }
    const batchId = this.batchId();
    const targets = ids.slice();
    this.discarding.set(true);

    forkJoin(targets.map((id) => this.imports.discardImportStaged(batchId, id)))
      .pipe(finalize(() => this.discarding.set(false)))
      .subscribe({
        next: () => {
          this.removeRows(targets);
          const label =
            targets.length === 1 ? 'Linha descartada.' : `${targets.length} linhas descartadas.`;
          this.snackBar.open(label, 'OK', { duration: 3000 });
        },
        error: (e) =>
          this.snackBar.open(this.errorText(e, 'Falha ao descartar as linhas.'), 'OK', {
            duration: 6000,
          }),
      });
  }

  /** Remove da view + da seleção, e corrige a página se ela deixou de existir. */
  private removeRows(ids: readonly string[]): void {
    const removed = new Set(ids);
    this.rows.update((rows) => rows.filter((r) => !removed.has(r.stagedId)));
    this.selection.deselect(...ids);
    if (this.expandedId() && removed.has(this.expandedId() as string)) {
      this.expandedId.set(null);
    }
    const lastPage = Math.max(0, Math.ceil(this.rows().length / this.pageSize()) - 1);
    if (this.pageIndex() > lastPage) {
      this.pageIndex.set(lastPage);
    }
  }

  // --- Commit ---

  confirm(): void {
    const rows = this.rows();
    // Só as REALMENTE prontas (conta + valor + data válidos) — mesma régua de `isReadyToCommit`
    // usada em `buildCommitRequest`. Uma linha com conta escolhida mas valor/data inválidos NÃO
    // entra aqui: nunca chega a virar PATCH nem commit, então nunca dispara o erro genérico do
    // backend (que, além de expor o UUID da staged, derrubaria o lote inteiro — commit() é
    // `@Transactional` único).
    const ready = rows.filter(isReadyToCommit);
    if (ready.length === 0) {
      this.snackBar.open(
        'Nenhuma linha pronta pra lançar — verifique conta, valor e data de cada uma.',
        'OK',
        { duration: 4000 },
      );
      return;
    }
    const id = this.batchId();
    this.committing.set(true);

    // Persiste as edições (PATCH marca confiança 1.0) ANTES de lançar — o commit lê os valores
    // já gravados. forkJoin garante que todos os patches terminem antes do commit disparar.
    const patches = ready.map((r) =>
      this.imports.patchImportStaged(id, r.stagedId, { fields: this.patchFieldsOf(r) }),
    );

    forkJoin(patches)
      .pipe(
        switchMap(() => this.imports.commitImport(id, buildCommitRequest(rows))),
        catchError((e) => {
          this.snackBar.open(this.errorText(e, 'Falha ao lançar as transações.'), 'OK', {
            duration: 6000,
          });
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
    this.duplicateConflict.set(null);
    this.selection.clear();
    this.pageIndex.set(0);
    this.expandedId.set(null);
  }

  /** `trackBy` da tabela: identidade da linha é o stagedId, não a posição. */
  readonly trackRow = (_: number, row: ReviewRow): string => row.stagedId;

  // --- Helpers de template (delegam à lógica pura) ---

  confPct(row: ReviewRow, key: ReviewFieldKey): string {
    return formatConfidence(row.confidences[key]);
  }

  confClass(row: ReviewRow, key: ReviewFieldKey): 'low' | 'high' {
    return confidenceLevel(row.confidences[key]);
  }

  private errorText(error: unknown, fallback: string): string {
    const err = error as { error?: { message?: string } };
    return err?.error?.message ?? fallback;
  }
}
