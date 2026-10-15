# Spec: Revisão de importação — cartão single-receipt + filtro 1/1

**Data:** 2026-10-15
**Status:** aprovado
**Stack:** @tech.md · Domínio: @domain.md
**Specs relacionadas:**
- `docs/superpowers/specs/2026-07-30-extracao-fase2-revisao-em-lote-design.md` (revisão em lote, #201)
- `docs/superpowers/specs/2026-08-06-import-itau-parcelamento-design.md` (parcelamento Itaú)

## 1. Contexto

A revisão em lote (#201, Fase 2 metade B) generalizou a tela para N transações via
`mat-table` compacta com paginação, seleção e bulk apply. O caso degenerado **1 linha**
ficou funcional mas subótimo: paginador escondido (`MIN_PAGE_SIZE = 10`), checkbox de
cabeçalho sem propósito, bulk-toolbar sem uso — o usuário via uma "mini-tabela" sem a
estrutura fazer sentido. UX subutilizada para o caso mais comum de importação
(1 foto de comprovante = 1 transação).

Simultaneamente, `installmentInfo()` (helper puro em `import-utils.ts`) trata qualquer
`installment_number`/`installment_total` não-null como parcela. O caso `1/1` é degenerado
(uma única parcela não é parcelamento) e nunca deveria aparecer como "Parcelada" — mas
como o caminho de extração pode popular esses campos por ruído, o usuário via a linha com
badge "Vai criar parcelamento completo (1 parcelas)" mesmo o `ImportService.commit`
gravando avulsa (o guard backend exige `installmentTotal > 1`, ver
`ImportService.java:494-498`).

As duas mudanças são puramente frontend e não mexem em contrato de API, schema nem regra
de negócio — por isso couberam como spec retroativo desta entrega, em vez de nova rota no
roadmap.

## 2. Decisões

| # | Decisão | Escolha | Alternativa descartada |
|---|---|---|---|
| a | Layout quando há 1 staged | Cartão único (`mat-card`) com todos os campos visíveis, sem tabela/seleção/bulk/paginação | Manter `mat-table` 1 linha; chrome redundante (paginador escondido, checkbox vazio, bulk sem uso) |
| b | Onde mora o gatilho | `singleReceiptView` computed no `ImportComponent` (`stage === 'review' && !readOnly() && rows().length === 1`) | Diretiva estrutural `[ngIf]`; computed já é o padrão do componente (signals-first, Zoneless) |
| c | Reuso de handlers | Mesmos `onAmountBlurRow`, `onDateChange`, `updateField`, `confirm`, `discardRow` — zero chamada HTTP nova, zero método novo | Variante dedicada; duplicaria a lógica de patch + commit sem ganho |
| d | Filtro 1/1 em `installmentInfo()` | Devolver `null` quando `number === 1 && total === 1` (caso degenerado = avulsa) | Manter como parcela 1/1; semanticamente errado (uma única parcela não é parcelamento) e divergente do que `commit` grava |
| e | Onde mora o filtro | Função pura `installmentInfo()` em `import-utils.ts` (camada sem dependência Angular) | Filtro no template (`@if (installmentInfo(row).number > 1 \|\| ...)`); espalharia regra de sanidade por N call sites |

**(a) Cartão único, sem chrome de tabela.** É o análogo direto do formulário de cadastro
(`transaction-form.ts`), porém pré-preenchido pelo OCR — mesma mentalidade de "lançar uma
transação". A confiança por campo (mantida) é o que diferencia "lançar" de "lançar com
atenção". Nenhuma nova regra de negócio — é puro redesenho de chrome.

**(b) `singleReceiptView` como computed.** Centraliza o gate num único lugar. Outros
helpers que dependem do mesmo flag leem o mesmo signal. Decisão coerente com o resto do
componente, que usa signals para todos os outros flags de UI (`stage`, `readOnly`,
`loadingStaged`).

**(c) Reusa os handlers existentes.** `patchFieldsOf` e `commit()` são chamados pelos
mesmos métodos que a tabela em lote já chama. A diferença é puramente de renderização —
não há ramo "commit single" vs "commit batch". Se o backend precisar divergir um dia,
continua sendo um só lugar.

**(d) 1/1 vira avulsa no frontend.** Sem o filtro, alguma fonte futura (mock, novo
extrator) pode popular `1/1` por ruído e a UI mostraria "Parcelada" + ícone "Vai criar
parcelamento completo (1 parcelas)" — incoerente com o que `ImportService.commit` grava
(`totalInstallments=null`, sem `InstallmentGroup`). Backend já filtra `installmentTotal > 1`;
frontend espelha o mesmo gate pra UI não mentir.

**(e) Filtro na função pura.** `installmentInfo()` é chamada por `groupRowsByInstallment`,
`isFirstInstallment`, `installmentWarning`, `installmentBadgeText` e `toRow` — o filtro
propaga automaticamente pra todos os call sites. Espalhar no template exigiria replicar
a condição em cada uso.

## 3. Mudanças

### 3.1 `frontend/src/app/features/import/import.ts`

```ts
/** Ativa quando há exatamente 1 linha PENDING e não é só-leitura. */
readonly singleReceiptView = computed(
  () => this.stage() === 'review' && !this.readOnly() && this.rows().length === 1,
);
singleRow(): ReviewRow | null {
  return this.rows().length === 1 ? this.rows()[0] : null;
}
```

### 3.2 `frontend/src/app/features/import/import.html`

Bloco dentro de `@if (stage() === 'review')`:

```
@if (singleReceiptView()) {
  @if (singleRow(); as row) {
    <mat-card appearance="outlined" class="single-receipt-card" data-testid="single-receipt-card">
      <!-- Valor, Data, Tipo, Descrição, Método, Conta, Categoria — badges de confiança -->
    </mat-card>
  }
} @else {
  <!-- Tabela em lote + paginador + bulk-toolbar (inalterado) -->
}
```

### 3.3 `frontend/src/app/features/import/import.scss`

Grid 2 colunas (responsivo <600px → 1 coluna), `.single-receipt-card` espelha o padrão
de `.field-grid`.

### 3.4 `frontend/src/app/features/import/import-utils.ts`

Filtro 1/1 em `installmentInfo()`:

```ts
if (number === 1 && total === 1) {
  return null;
}
```

## 4. Teste

Coberto por:

- `import.spec.ts` — componente: 1 linha renderiza `[data-testid="single-receipt-card"]` e
  oculta `tr.review-row`; N >= 2 mantém tabela em lote.
- `import-utils.spec.ts` — `installmentInfo({installment_number:1, installment_total:1})`
  devolve `null`; casos válidos (`{4, 6}`) seguem inalterados.

## 5. Fora de escopo

- **Preview da imagem original** ao lado dos campos. O backend não guarda o arquivo
  (`import_batches` tem `source_hash`/`source_filename` mas **não** `storage_path` — colunas
  V23/V26/V28 verificadas); mostrar preview exige migration nova + endpoint
  `GET /api/imports/{id}/file` — escopo separado.
- **Auto-confirmar 1 linha com confiança total** (pular o "Confirmar e lançar"). Quebra o
  invariante do projeto: toda extração passa por revisão visual.
- **Reaproveitar `transaction-form.ts` como componente filho** do cartão. Hoje o cartão é
  inline; se a complexidade crescer (preview, sugestões), extrair.
- **Mover o filtro 1/1 para o backend.** `ImportService.commit()` já filtra
  `installmentTotal > 1`; duplicar no backend é redundante — o frontend precisa espelhar
  pra UI não mentir, não pra "blindar" o backend.
- **Layout dedicado também para N=0.** Caso impossível hoje (`stage === 'review'` exige ≥1
  staged PENDING); o cartão de "fallback manual" já cobre o caminho de falha (`FAILED`).