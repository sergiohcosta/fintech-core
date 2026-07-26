import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter, Router } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

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
  id: 'acc-1', name: 'Conta', type: 'CHECKING',
  countInLiquidBalance: true, countInNetWorth: true, active: true, balance: 0,
};
const extractedBatch: ImportBatchResponseDTO = {
  id: 'b1', importMode: 'NEW_TRANSACTIONS', sourceType: 'IMAGE', status: 'EXTRACTED',
};
const failedBatch: ImportBatchResponseDTO = {
  id: 'b2', importMode: 'NEW_TRANSACTIONS', sourceType: 'IMAGE', status: 'FAILED',
};
const staged: StagedTransactionResponseDTO = {
  id: 's1', batchId: 'b1', requiresReview: true, status: 'PENDING', overallConfidence: 0.8,
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

    expect(patchSpy).toHaveBeenCalledWith(
      'b1', 's1', { fields: expect.objectContaining({ amount: '127.5' }) },
    );
    expect(commitSpy).toHaveBeenCalledWith(
      'b1', { items: [{ stagedId: 's1', accountId: 'acc-1', categoryId: null }] },
    );
    expect(navSpy).toHaveBeenCalledWith(['/transactions']);
  });
});
