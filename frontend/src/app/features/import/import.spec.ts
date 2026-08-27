import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { ImportComponent } from './import';
import { ImportsService } from '../../core/api/imports/imports.service';
import { AccountsService } from '../../core/api/accounts/accounts.service';
import { CategoriesService } from '../../core/api/categories/categories.service';
import type {
  AccountResponse,
  ImportBatchResponseDTO,
  StagedTransactionResponseDTO,
} from '../../core/api/fintechSaaSAPI.schemas';

const account: AccountResponse = {
  id: 'acc-1',
  name: 'Conta',
  type: 'CHECKING',
  countInLiquidBalance: true,
  countInNetWorth: true,
  active: true,
  balance: 0,
};
const extractedBatch: ImportBatchResponseDTO = {
  id: 'b1',
  importMode: 'NEW_TRANSACTIONS',
  sourceType: 'IMAGE',
  status: 'EXTRACTED',
};
const failedBatch: ImportBatchResponseDTO = {
  id: 'b2',
  importMode: 'NEW_TRANSACTIONS',
  sourceType: 'IMAGE',
  status: 'FAILED',
};
const staged: StagedTransactionResponseDTO = {
  id: 's1',
  batchId: 'b1',
  requiresReview: true,
  status: 'PENDING',
  overallConfidence: 0.8,
  fields: {
    amount: { value: 127.5, confidence: 0.98 },
    transaction_date: { value: '2026-06-28', confidence: 0.5 },
    description: { value: 'PADARIA', confidence: 0.9 },
    direction: { value: 'debit', confidence: 0.99 },
  },
};

function fakeImage(): File {
  return new File([new Uint8Array([1, 2, 3])], 'comprovante.jpg', { type: 'image/jpeg' });
}

/** N staged PENDING (s1..sN) — fixture das cenas de lote (seleção, paginação, descarte). */
function manyStaged(n: number): StagedTransactionResponseDTO[] {
  return Array.from({ length: n }, (_, i) => ({ ...staged, id: `s${i + 1}` }));
}

describe('ImportComponent', () => {
  let imports: ImportsService;
  let accounts: AccountsService;
  let categories: CategoriesService;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ImportComponent, NoopAnimationsModule],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    imports = TestBed.inject(ImportsService);
    accounts = TestBed.inject(AccountsService);
    categories = TestBed.inject(CategoriesService);
    router = TestBed.inject(Router);
    // As chamadas do cliente Orval são sobrecarregadas (overload por `observe`), o que quebra a
    // inferência de tipo do vi.spyOn(...).mockReturnValue(...). `as any` aqui é o mesmo padrão já
    // usado em dashboard.spec.ts/invoice-list.spec.ts/account-list.spec.ts — só neste ponto de
    // mocking, nunca na lógica de produção.
    vi.spyOn(accounts, 'listAccounts').mockReturnValue(of([account]) as any);
    vi.spyOn(categories, 'listCategories').mockReturnValue(of([]) as any);
  });

  it('carrega contas no init e começa no estágio de upload', () => {
    const c = TestBed.createComponent(ImportComponent).componentInstance;
    c.ngOnInit();
    expect(c.accounts()).toHaveLength(1);
    expect(c.stage()).toBe('upload');
  });

  it('extrai a imagem e mostra a revisão com os campos', () => {
    vi.spyOn(imports, 'createImport').mockReturnValue(of(extractedBatch) as any);
    vi.spyOn(imports, 'listImportStaged').mockReturnValue(of([staged]) as any);

    const c = TestBed.createComponent(ImportComponent).componentInstance;
    c.ngOnInit();
    c.selectedFile.set(fakeImage());
    c.upload();

    expect(c.stage()).toBe('review');
    expect(c.rows()).toHaveLength(1);
    expect(c.rows()[0].amount).toBe('127.5');
    expect(c.rows()[0].direction).toBe('debit');
    // Sem conta escolhida ainda → não pode confirmar.
    expect(c.canConfirm()).toBe(false);
  });

  it('batch FAILED cai no fallback manual e não carrega staged', () => {
    vi.spyOn(imports, 'createImport').mockReturnValue(of(failedBatch) as any);
    const listSpy = vi.spyOn(imports, 'listImportStaged').mockReturnValue(of([]) as any);

    const c = TestBed.createComponent(ImportComponent).componentInstance;
    c.ngOnInit();
    c.selectedFile.set(fakeImage());
    c.upload();

    expect(c.stage()).toBe('failed');
    expect(listSpy).not.toHaveBeenCalled();
  });

  it('confirmar faz patch das edições, commit e navega para transações', () => {
    vi.spyOn(imports, 'createImport').mockReturnValue(of(extractedBatch) as any);
    vi.spyOn(imports, 'listImportStaged').mockReturnValue(of([staged]) as any);
    const patchSpy = vi.spyOn(imports, 'patchImportStaged').mockReturnValue(of(staged) as any);
    const commitSpy = vi
      .spyOn(imports, 'commitImport')
      .mockReturnValue(of({ ...extractedBatch, status: 'COMMITTED' }) as any);
    const navSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    const c = TestBed.createComponent(ImportComponent).componentInstance;
    c.ngOnInit();
    c.selectedFile.set(fakeImage());
    c.upload();
    c.updateField('s1', 'accountId', 'acc-1');

    expect(c.canConfirm()).toBe(true);
    c.confirm();

    expect(patchSpy).toHaveBeenCalledWith('b1', 's1', {
      fields: expect.objectContaining({ amount: '127.5' }),
    });
    expect(commitSpy).toHaveBeenCalledWith('b1', {
      items: [{ stagedId: 's1', accountId: 'acc-1', categoryId: null }],
    });
    expect(navSpy).toHaveBeenCalledWith(['/transactions']);
  });

  it('409 (arquivo já importado) mostra conflito em vez de snackbar, sem criar batch', () => {
    const conflictError = {
      status: 409,
      error: { batchId: 'b-antigo', createdAt: '2026-07-01T10:00:00', filename: 'extrato.csv' },
    };
    vi.spyOn(imports, 'createImport').mockReturnValue(throwError(() => conflictError) as any);

    const c = TestBed.createComponent(ImportComponent).componentInstance;
    c.ngOnInit();
    c.selectedFile.set(fakeImage());
    c.upload();

    expect(c.stage()).toBe('upload'); // nenhum batch foi criado
    expect(c.duplicateConflict()).toEqual({
      batchId: 'b-antigo',
      createdAt: '2026-07-01T10:00:00',
      filename: 'extrato.csv',
    });
  });

  it('"importar mesmo assim" reenvia com force=true e "cancelar" limpa o conflito', () => {
    const conflictError = {
      status: 409,
      error: { batchId: 'b-antigo', createdAt: null, filename: null },
    };
    const createSpy = vi
      .spyOn(imports, 'createImport')
      .mockReturnValueOnce(throwError(() => conflictError) as any)
      .mockReturnValueOnce(of(extractedBatch) as any);
    vi.spyOn(imports, 'listImportStaged').mockReturnValue(of([staged]) as any);

    const c = TestBed.createComponent(ImportComponent).componentInstance;
    c.ngOnInit();
    c.selectedFile.set(fakeImage());
    c.upload();
    expect(c.duplicateConflict()).not.toBeNull();

    c.importAnyway();
    expect(createSpy).toHaveBeenLastCalledWith(expect.anything(), { force: true });
    expect(c.duplicateConflict()).toBeNull();
    expect(c.stage()).toBe('review');
  });

  // --- Revisão em lote (Fase 2 metade B) ---

  /** Sobe o componente já no estágio de revisão com `list` staged carregadas. */
  function reviewing(list: StagedTransactionResponseDTO[]) {
    vi.spyOn(imports, 'createImport').mockReturnValue(of(extractedBatch) as any);
    vi.spyOn(imports, 'listImportStaged').mockReturnValue(of(list) as any);
    const fixture = TestBed.createComponent(ImportComponent);
    const c = fixture.componentInstance;
    fixture.detectChanges();
    c.selectedFile.set(fakeImage());
    c.upload();
    fixture.detectChanges();
    return { fixture, c };
  }

  it('seleção múltipla marca e desmarca exatamente as linhas escolhidas', () => {
    const { c } = reviewing(manyStaged(3));

    c.toggleRow('s1');
    c.toggleRow('s3');
    expect(c.selectedIds()).toEqual(['s1', 's3']);
    expect(c.isSelected('s2')).toBe(false);

    c.toggleRow('s1');
    expect(c.selectedIds()).toEqual(['s3']);
  });

  it('"selecionar todas" age só sobre a página atual', () => {
    const { c } = reviewing(manyStaged(30));
    c.pageSize.set(10);

    c.toggleAllOnPage();
    expect(c.selectedIds()).toHaveLength(10);
    expect(c.allOnPageSelected()).toBe(true);

    c.toggleAllOnPage();
    expect(c.selectedIds()).toHaveLength(0);
  });

  it('toolbar de ações em massa só aparece com seleção não-vazia', () => {
    const { fixture, c } = reviewing(manyStaged(3));
    const toolbar = () => fixture.nativeElement.querySelector('[data-testid="bulk-toolbar"]');

    expect(c.hasSelection()).toBe(false);
    expect(toolbar()).toBeNull();

    c.toggleRow('s2');
    fixture.detectChanges();
    expect(toolbar()).not.toBeNull();

    c.clearSelection();
    fixture.detectChanges();
    expect(toolbar()).toBeNull();
  });

  it('definir conta em massa aplica só às selecionadas, sem chamada de API', () => {
    const { c } = reviewing(manyStaged(12));
    const patchSpy = vi.spyOn(imports, 'patchImportStaged');

    c.toggleRow('s1');
    c.toggleRow('s2');
    c.toggleRow('s5');
    c.applyAccountToSelected('acc-1');

    const byId = (id: string) => c.rows().find((r) => r.stagedId === id);
    expect(byId('s1')!.accountId).toBe('acc-1');
    expect(byId('s2')!.accountId).toBe('acc-1');
    expect(byId('s5')!.accountId).toBe('acc-1');
    expect(byId('s3')!.accountId).toBeNull();
    expect(patchSpy).not.toHaveBeenCalled();
  });

  it('descartar selecionadas chama o endpoint por linha, remove da tabela e limpa a seleção', () => {
    const { c } = reviewing(manyStaged(4));
    const discardSpy = vi
      .spyOn(imports, 'discardImportStaged')
      .mockReturnValue(of({ ...staged, status: 'DISCARDED' }) as any);

    c.toggleRow('s2');
    c.toggleRow('s4');
    c.discardSelected();

    expect(discardSpy).toHaveBeenCalledTimes(2);
    expect(discardSpy).toHaveBeenCalledWith('b1', 's2');
    expect(discardSpy).toHaveBeenCalledWith('b1', 's4');
    expect(c.rows().map((r) => r.stagedId)).toEqual(['s1', 's3']);
    expect(c.selectedIds()).toEqual([]);
  });

  it('descartar uma linha individual não mexe na seleção das outras', () => {
    const { c } = reviewing(manyStaged(3));
    vi.spyOn(imports, 'discardImportStaged').mockReturnValue(of(staged) as any);

    c.toggleRow('s1');
    c.discardRow('s3');

    expect(c.rows().map((r) => r.stagedId)).toEqual(['s1', 's2']);
    expect(c.selectedIds()).toEqual(['s1']);
  });

  it('falha no descarte mantém a linha na tabela', () => {
    const { c } = reviewing(manyStaged(2));
    vi.spyOn(imports, 'discardImportStaged').mockReturnValue(
      throwError(() => ({ error: { message: 'boom' } })) as any,
    );

    c.discardRow('s1');

    expect(c.rows()).toHaveLength(2);
    expect(c.discarding()).toBe(false);
  });

  it('staged já DISCARDED não entra na tabela', () => {
    const { c } = reviewing([staged, { ...staged, id: 's2', status: 'DISCARDED' }]);
    expect(c.rows().map((r) => r.stagedId)).toEqual(['s1']);
  });

  it('paginação client-side fatia as linhas e PRESERVA a seleção entre páginas', () => {
    const { c } = reviewing(manyStaged(30));
    c.pageSize.set(10);

    expect(c.pagedRows().map((r) => r.stagedId)).toEqual([
      's1',
      's2',
      's3',
      's4',
      's5',
      's6',
      's7',
      's8',
      's9',
      's10',
    ]);
    c.toggleRow('s2');

    c.onPage({ pageIndex: 2, pageSize: 10, length: 30 });
    expect(c.pagedRows()[0].stagedId).toBe('s21');
    // Seleção é keyed por stagedId — trocar de página não a invalida (decisão de execução).
    expect(c.selectedIds()).toEqual(['s2']);

    c.toggleRow('s21');
    c.onPage({ pageIndex: 0, pageSize: 10, length: 30 });
    expect(c.selectedIds()).toEqual(['s2', 's21']);
    expect(c.isSelected('s2')).toBe(true);
  });

  it('edição de linha fora da página atual é preservada', () => {
    const { c } = reviewing(manyStaged(30));
    c.pageSize.set(10);

    c.updateField('s25', 'accountId', 'acc-1');
    c.onPage({ pageIndex: 2, pageSize: 10, length: 30 });

    expect(c.pagedRows().find((r) => r.stagedId === 's25')!.accountId).toBe('acc-1');
  });

  it('fluxo de imagem única (Fase 1): 1 linha na tabela e nenhum paginador', () => {
    const { fixture, c } = reviewing([staged]);

    expect(c.rows()).toHaveLength(1);
    expect(c.pagedRows()).toHaveLength(1);
    expect(c.showPaginator()).toBe(false);
    expect(fixture.nativeElement.querySelector('mat-paginator')).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('tr.review-row')).toHaveLength(1);
  });

  it('contagem exibida reflete "prontas de pendentes" com o gate relaxado', () => {
    const { c } = reviewing(manyStaged(4));
    c.updateField('s1', 'accountId', 'acc-1');
    c.updateField('s3', 'accountId', 'acc-1');

    expect(c.pendingCount()).toBe(4);
    expect(c.readyCount()).toBe(2);
    expect(c.canConfirm()).toBe(true);
  });

  it('linha com duplicateCandidateOf preenchido carrega a flag na row', () => {
    const stagedDuplicado = { ...staged, id: 's2', duplicateCandidateOf: 's1' };
    vi.spyOn(imports, 'createImport').mockReturnValue(of(extractedBatch) as any);
    vi.spyOn(imports, 'listImportStaged').mockReturnValue(of([staged, stagedDuplicado]) as any);

    const c = TestBed.createComponent(ImportComponent).componentInstance;
    c.ngOnInit();
    c.selectedFile.set(fakeImage());
    c.upload();

    expect(c.rows()[0].duplicateCandidateOf).toBeNull();
    expect(c.rows()[1].duplicateCandidateOf).toBe('s1');
  });
});

/** Navegação direta pra um batch existente (histórico → `/import/:id`) — reusa o mesmo componente,
 *  mas carrega do backend em vez de partir do estágio de upload. */
describe('ImportComponent — carregado por :id da rota', () => {
  let imports: ImportsService;
  let accounts: AccountsService;
  let categories: CategoriesService;

  function configureWithRouteId(id: string): void {
    TestBed.configureTestingModule({
      imports: [ImportComponent, NoopAnimationsModule],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id }) } },
        },
      ],
    });
    imports = TestBed.inject(ImportsService);
    accounts = TestBed.inject(AccountsService);
    categories = TestBed.inject(CategoriesService);
    vi.spyOn(accounts, 'listAccounts').mockReturnValue(of([account]) as any);
    vi.spyOn(categories, 'listCategories').mockReturnValue(of([]) as any);
  }

  it('carrega o batch e a staged existentes direto no init, sem passar por upload', () => {
    configureWithRouteId('b1');
    vi.spyOn(imports, 'getImport').mockReturnValue(of(extractedBatch) as any);
    vi.spyOn(imports, 'listImportStaged').mockReturnValue(of([staged]) as any);

    const c = TestBed.createComponent(ImportComponent).componentInstance;
    c.ngOnInit();

    expect(c.stage()).toBe('review');
    expect(c.rows()).toHaveLength(1);
    expect(c.readOnly()).toBe(false);
  });

  it('batch COMMITTED entra em modo somente-leitura', () => {
    configureWithRouteId('b1');
    const committedBatch: ImportBatchResponseDTO = { ...extractedBatch, status: 'COMMITTED' };
    vi.spyOn(imports, 'getImport').mockReturnValue(of(committedBatch) as any);
    vi.spyOn(imports, 'listImportStaged').mockReturnValue(
      of([{ ...staged, status: 'CONFIRMED' }]) as any,
    );

    const c = TestBed.createComponent(ImportComponent).componentInstance;
    c.ngOnInit();

    expect(c.readOnly()).toBe(true);
  });
});
