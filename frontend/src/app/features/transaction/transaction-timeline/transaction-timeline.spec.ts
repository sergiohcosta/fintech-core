// transaction-timeline.spec.ts
//
// Testa a lógica do shell component da Timeline de Transações: carregamento inicial,
// navegação com queryParams, troca de viewMode e filtro client-side por descrição.
//
// Estratégia de isolamento: overrideComponent() com template inline vazio E imports vazio
// substitui templateUrl/styleUrl/imports externos, evitando a necessidade de fetch dos
// templates dos sub-componentes via ResourceLoader em jsdom. Os comportamentos testados são
// todos na camada de lógica (signals + service) — sem renderização de template.
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';
import { provideZonelessChangeDetection } from '@angular/core';
import { TransactionTimelineComponent } from './transaction-timeline';
import { TransactionsService } from '../../../core/api/transactions/transactions.service';

describe('TransactionTimelineComponent', () => {
  // Mocks declarados no escopo do describe para serem reconfigurados a cada teste
  const listTransactions = vi.fn().mockReturnValue(of([]));
  const navigate = vi.fn();
  const snackBarOpen = vi.fn();

  beforeEach(() => {
    localStorage.clear();
    listTransactions.mockClear();
    listTransactions.mockReturnValue(of([]));
    navigate.mockClear();
    snackBarOpen.mockClear();

    // overrideComponent() troca a metadata inteira do componente:
    // - template inline vazio (evita fetch de templateUrl em jsdom)
    // - imports vazio (evita resolução recursiva dos sub-componentes calendar/grouped/horizontal)
    // - styles vazio (evita fetch de styleUrl)
    // Sem compileComponents() — a compilação ocorre sob demanda em createComponent(),
    // que já reconhece o override e usa o template inline, não a templateUrl.
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: TransactionsService, useValue: { listTransactions } },
        { provide: Router, useValue: { navigate } },
        { provide: MatSnackBar, useValue: { open: snackBarOpen } },
      ],
    }).overrideComponent(TransactionTimelineComponent, {
      set: {
        template: '<div></div>',
        styles: [],
        imports: [], // remove sub-componentes do escopo — não precisam ser resolvidos
      },
    });
  });

  it('carrega transações na criação', async () => {
    // O effect() no constructor detecta mudança nos filtros server-side e chama listTransactions
    const fixture = TestBed.createComponent(TransactionTimelineComponent);
    await fixture.whenStable();
    expect(listTransactions).toHaveBeenCalledTimes(1);
  });

  it('goToTransactionList navega com queryParams mapeados', async () => {
    const fixture = TestBed.createComponent(TransactionTimelineComponent);
    await fixture.whenStable();
    const comp = fixture.componentInstance;
    comp.filters.set({
      accountIds: ['a1', 'a2'], statuses: ['PAID'], types: ['EXPENSE'],
      startDate: '2026-06-01', endDate: '2026-06-30', description: 'luz', viewMode: 'calendar',
    });
    comp.goToTransactionList();
    expect(navigate).toHaveBeenCalledWith(['/transactions'], {
      queryParams: { accountIds: 'a1,a2', status: 'PAID', type: 'EXPENSE',
        startDate: '2026-06-01', endDate: '2026-06-30', description: 'luz' },
    });
  });

  it('onTabChange troca o viewMode', async () => {
    const fixture = TestBed.createComponent(TransactionTimelineComponent);
    await fixture.whenStable();
    const comp = fixture.componentInstance;
    // VIEW_MODES = ['calendar', 'grouped', 'horizontal']; índice 1 → 'grouped'
    comp.onTabChange(1);
    expect(comp.filters().viewMode).toBe('grouped');
  });

  it('visibleTransactions filtra por descrição client-side', async () => {
    // mockReturnValueOnce ANTES de createComponent → o effect inicial recebe os 2 itens
    listTransactions.mockReturnValueOnce(of([
      { id: '1', description: 'Conta de luz', amount: 100, date: '2026-06-10', type: 'EXPENSE', status: 'PAID' },
      { id: '2', description: 'Mercado', amount: 50, date: '2026-06-11', type: 'EXPENSE', status: 'PAID' },
    ]));
    const fixture = TestBed.createComponent(TransactionTimelineComponent);
    await fixture.whenStable();
    const comp = fixture.componentInstance;
    // description é client-side: não aciona fetch, apenas recomputa visibleTransactions
    comp.filters.update(f => ({ ...f, description: 'luz' }));
    expect(comp.visibleTransactions().map(t => t.id)).toEqual(['1']);
  });
});
