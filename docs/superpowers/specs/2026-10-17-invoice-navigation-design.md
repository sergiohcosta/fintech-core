# Spec de Design: Navegação Anterior / Próxima na Página de Detalhes da Fatura

**Issue:** #210 — `feat: página de detalhes de fatura precisa de navegação de próxima/anterior`  
**Data:** 17 de Outubro de 2026  
**Status:** Pronto para Implementação  
**Autor:** Antigravity (Arquiteto)  
**Executor:** Claude Code CLI (Worktree isolada)

---

## 1. Contexto & Problema

Na tela de detalhes de fatura (`/invoices/:id`), o usuário visualiza as informações de uma fatura específica de cartão de crédito (resumo de receitas/despesas, breakdown por categoria, compras parceladas e transações normais).

Contudo:
1. **Falta de Continuidade:** Não existe forma de alternar para a fatura do mês anterior ou seguinte sem sair da tela e retornar à listagem geral de faturas ou ao extrato da conta.
2. **Incompatibilidade com Reuso de Componente:** O componente `InvoiceDetail` atualmente obtém o ID da fatura de forma estática no `ngOnInit` via `this.route.snapshot.paramMap.get('id')`. No Angular, navegar entre rotas com o mesmo path param (e.g., de `/invoices/inv-1` para `/invoices/inv-2`) reutiliza a instância do componente sem disparar `ngOnInit` novamente, quebrando qualquer link direto.

---

## 2. Requisitos & Casos de Uso

### Casos de Uso
1. **Fatura Intermediária:** O usuário visualiza a fatura de Junho/2026. Ambos os botões (anterior e próximo) devem estar habilitados.
   - Clicar em "Anterior" direciona para a fatura de Maio/2026 (`/invoices/<id-maio>`).
   - Clicar em "Próxima" direciona para a fatura de Julho/2026 (`/invoices/<id-julho>`).
2. **Primeira Fatura Registrada:** Quando a fatura for a mais antiga da conta, o botão "Anterior" deve estar desabilitado (`disabled`).
3. **Última Fatura Registrada:** Quando a fatura for a mais recente da conta, o botão "Próxima" deve estar desabilitado (`disabled`).
4. **Atualização Dinâmica:** Ao navegar para uma nova fatura, o componente deve atualizar todos os dados (detalhes, transações, parcelamentos e breakdown) sem recarregar a aplicação.
5. **Acessibilidade & Tooltips:** Botões devem possuir `aria-label` descritivo e `matTooltip` indicando o mês/ano de destino (ex.: `Fatura anterior: Maio/2026`).

---

## 3. Arquitetura da Solução

### 3.1. Reatividade com `ActivatedRoute.paramMap`
Em vez de depender apenas do snapshot, o componente passa a escutar `this.route.paramMap`:

```typescript
// frontend/src/app/features/invoice/invoice-detail/invoice-detail.ts
export class InvoiceDetail implements OnInit {
  private route = inject(ActivatedRoute);
  private destroyRef = inject(DestroyRef);
  ...

  ngOnInit(): void {
    if (this.route.paramMap) {
      this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(params => {
        const id = params.get('id');
        if (id) {
          this.loadInvoice(id);
        }
      });
    } else if (this.route.snapshot?.paramMap) {
      const id = this.route.snapshot.paramMap.get('id');
      if (id) {
        this.loadInvoice(id);
      }
    }
  }
```

### 3.2. Obtenção das Faturas Irmãs (`accountInvoices`)
Quando a fatura é carregada, busca-se a lista de faturas da mesma conta (`accountId`):

```typescript
accountInvoices = signal<InvoiceResponseDTO[]>([]);

private loadInvoice(id: string): void {
  this.invoicesService.getInvoice(id).subscribe({
    next: (inv) => {
      this.invoice.set(inv);
      this.loadAccountInvoices(inv.accountId);
    },
    error: () => this.snackBar.open('Fatura não encontrada.', 'Fechar', { duration: 5000 })
  });

  this.transactionsService.listTransactions({ invoiceId: id }).subscribe({
    next: (txs) => this.transactions.set(txs),
    error: () => this.snackBar.open('Erro ao carregar transações.', 'Fechar', { duration: 5000 })
  });
}

private loadAccountInvoices(accountId: string): void {
  this.invoicesService.listInvoices({ accountId }).subscribe({
    next: (list) => {
      // Ordenação cronológica crescente: (ano * 12 + mês)
      const sorted = [...list].sort((a, b) =>
        (a.referenceYear * 12 + a.referenceMonth) - (b.referenceYear * 12 + b.referenceMonth)
      );
      this.accountInvoices.set(sorted);
    },
    error: () => {}
  });
}
```

### 3.3. Sinais Computados de Navegação
```typescript
currentIndex = computed(() => {
  const current = this.invoice();
  if (!current) return -1;
  return this.accountInvoices().findIndex(i => i.id === current.id);
});

prevInvoice = computed(() => {
  const idx = this.currentIndex();
  return idx > 0 ? this.accountInvoices()[idx - 1] : null;
});

nextInvoice = computed(() => {
  const idx = this.currentIndex();
  const list = this.accountInvoices();
  return idx >= 0 && idx < list.length - 1 ? list[idx + 1] : null;
});
```

### 3.4. Template (`invoice-detail.html`)
Adicionar container `.title-with-nav` no cabeçalho com botões `mat-icon-button`:

```html
<div class="title-with-nav">
  <button mat-icon-button
          [disabled]="!prevInvoice()"
          [routerLink]="prevInvoice() ? ['/invoices', prevInvoice()!.id] : null"
          [matTooltip]="prevInvoice() ? 'Fatura anterior (' + prevInvoice()!.label + ')' : ''"
          aria-label="Fatura anterior">
    <mat-icon>chevron_left</mat-icon>
  </button>
  <h1>Fatura {{ inv.label }}</h1>
  <button mat-icon-button
          [disabled]="!nextInvoice()"
          [routerLink]="nextInvoice() ? ['/invoices', nextInvoice()!.id] : null"
          [matTooltip]="nextInvoice() ? 'Próxima fatura (' + nextInvoice()!.label + ')' : ''"
          aria-label="Próxima fatura">
    <mat-icon>chevron_right</mat-icon>
  </button>
</div>
```

*Nota:* Importar `RouterLink` em `InvoiceDetail.imports`.

### 3.5. Estilo SCSS (`invoice-detail.scss`)
```scss
.title-with-nav {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 8px;

  h1 {
    margin: 0;
  }

  button[mat-icon-button] {
    flex-shrink: 0;
  }
}
```

---

## 4. Plano de Testes Unitários (`invoice-detail.spec.ts`)

1. **Atualizar `makeRoute` e Mocks Base:**
   - Adicionar `paramMap: of(convertToParamMap({ id }))`.
   - Adicionar spy padrão para `invoicesService.listInvoices`.
2. **Cálculo de Anterior e Próxima:**
   - Cenário com 3 faturas: Mai/2026, Jun/2026, Jul/2026.
   - Em Jun/2026: `prevInvoice` = Mai/2026, `nextInvoice` = Jul/2026.
   - Em Mai/2026: `prevInvoice` = null, `nextInvoice` = Jun/2026.
   - Em Jul/2026: `prevInvoice` = Jun/2026, `nextInvoice` = null.
3. **Estado dos Botões no DOM:**
   - Verificar atributos `disabled` nos botões para os cenários acima.
