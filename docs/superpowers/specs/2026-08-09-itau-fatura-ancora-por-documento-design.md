# Spec: fatura importada ancora suas próprias linhas (Itaú)

**Data:** 2026-08-09
**Status:** proposto (aguardando aprovação)
**Origem:** validação manual pós-fix de coluna (spec `2026-08-07-itau-ancora-coluna-por-data-design.md`) — usuário reimportou a fatura de referência numa conta de teste real (`Teste Importacao`, dev) e perguntou por que a fatura de ago/2026 mostrava R$14.070,37 em vez dos R$15.860,53 impressos.
Stack: @tech.md · Domínio: @domain.md · Migrations: @database-schema.md

## 1. Contexto

### 1.1 O sintoma

Reimportação da fatura de referência (total impresso R$15.860,53, 123 lançamentos na seção
"Lançamentos: compras e saques" somando R$15.739,87 — igual ao já validado na spec anterior).
Depois de commitados, os 123 lançamentos NÃO caem todos na fatura de ago/2026:

| `closingDay` testado | Na fatura de ago/2026 | Fora dela |
|---|---|---|
| 5 (config. original da conta de teste) | 102 — R$13.983,56 | 21 — R$1.756,31 |
| 3 (medido no ciclo desta fatura) | 115 — R$15.005,79 | 8 — R$734,08 |
| 2 (medido em 24 faturas reais, vencimento dia 10) | 116 — R$15.080,66 | 7 — R$659,21 |

Mesmo com o `closingDay` da conta ajustado pro valor correto e medido, **7 lançamentos
persistem fora da fatura de agosto** — todos parcelas `>1/N` (parcela em andamento) com data
de compra antiga (nov/2025 a jun/2026).

### 1.2 Causa raiz

`ImportService.commit()` reusa `TransactionService.create()`, o MESMO caminho de um
lançamento manual — que sempre recalcula em que fatura a transação cai via
`resolveInvoiceMonth(purchaseDate, closingDay)`.

Isso é certo pra um lançamento manual (o usuário está registrando uma compra nova, o sistema
decide o ciclo). É **errado** pra uma linha vinda de uma fatura já emitida: o Itaú já decidiu
em que fatura aquela linha caiu — é o próprio documento que está sendo importado. Recalcular
por `closingDay` é reinventar uma decisão que já está no documento, e qualquer imprecisão na
configuração do `closingDay` da conta (que já se mostrou sensível — 3 valores testados nesta
mesma investigação) produz divergência.

O efeito é mais visível em parcelas `>1/N`: a data de compra é antiga (a parcela é de um
parcelamento iniciado meses atrás), então `resolveInvoiceMonth` manda a linha pra uma fatura
de meses atrás — mesmo a fatura importada mostrando, sem ambiguidade, que aquela parcela
está cobrada ESTE mês.

### 1.3 Escopo desta entrega

Corrige só o roteamento (a causa dos R$659,21 medidos). Os R$120,66 de diferença restante
vêm de seções fora do escopo do template (`Lançamentos internacionais`, produtos/serviços) —
parsing novo (moeda estrangeira, conversão), tratado como spec separada.

## 2. Decisões

| # | Decisão | Escolha | Alternativa descartada |
|---|---|---|---|
| a | Alcance do pin | TODA linha do documento (parcela 1, avulsa e parcela `>N`) ancora na fatura do documento | Só parcela 1/avulsa, deixando parcela `>1` como hoje — resolveria menos e deixaria a causa raiz pela metade |
| b | Onde carrega o fato "fatura alvo" | Nível de BATCH (`import_batches`, 2 colunas nullable: `target_invoice_reference_year/month`) | Por linha (repetir em cada `staged_transactions.fields`) — o documento tem UM vencimento só, redundância sem ganho |
| c | Como deriva o mês de referência do vencimento impresso | `referenceMonth = vencimento.minusMonths(1)` — mesma relação que `InvoiceService.createNewInvoice` já assume no caso `dueDay >= closingDay` (confirmado nas 45 faturas reais: vencimento sempre dia 10, sempre essa relação) | Derivar via `closingDay`/`dueDay` configurados na conta — reintroduziria a mesma fragilidade que causou o sintoma (config pode divergir do cartão real) |
| d | Escopo do template | Só `ItauFaturaTemplate` (único com conceito de "1 documento = 1 fatura com vencimento único") | Estender a CSV/OFX/heurística genérica — não fazem sentido: extrato é um período, não uma fatura fechada |
| e | Onde a lógica de override vive | Método interno novo em `TransactionService`, não exposto via `TransactionRequestDTO`/API pública | Adicionar campo opcional no DTO público — mudaria contrato REST sem necessidade (lançamento manual nunca precisa disso) |
| f | Lançamento manual | Comportamento 100% inalterado (`resolveInvoiceMonth` continua a regra) | — |

**(c) Por que não usar o `closingDay` configurado.** Essa investigação já mostrou o
`closingDay` de uma conta pode estar desalinhado do ciclo real do cartão (a própria conta de
teste passou por 3 valores até bater com o medido). Ancorar pelo vencimento IMPRESSO no
documento é independente dessa configuração — o dado já está certo no PDF, não precisa ser
recalculado.

**(a) Por que toda linha, não só parcela 1.** A fatura já é a resposta pronta de "que
lançamentos estão cobrados neste ciclo". Uma parcela `5/12` nela pertence a este ciclo tanto
quanto uma compra avulsa — não há razão pra tratar diferente. Casar essa parcela contra um
`InstallmentGroup` já existente no sistema continua fora de escopo (reconciliação de verdade,
Fase 4/5) — aqui só se corrige ONDE a transação avulsa cai, não se ela duplica um grupo.

## 3. Modelo de dados

```sql
ALTER TABLE import_batches
    ADD COLUMN target_invoice_reference_year  INTEGER,
    ADD COLUMN target_invoice_reference_month INTEGER;
```

Nullable, sem backfill — batches existentes (imagem, CSV, OFX, heurística genérica) não têm
esse conceito e permanecem `NULL`, preservando o comportamento atual. Só `ItauFaturaTemplate`
popula. Não exposto em `ImportBatchResponseDTO` nesta entrega — dado operacional interno,
mesmo tratamento dado à proveniência estruturada do V28.

**Seed:** `V24__seed_dev_import.sql` (batch de imagem) e `V27__seed_dev_import_csv.sql` (batch
CSV) recebem as 2 colunas explicitamente `NULL` — nenhum dos dois é fatura Itaú, então não se
aplica, mas a regra do dataset (`dataset.md`) é inviolável: coluna nova em tabela existente
sempre atualiza os INSERTs do seed.

## 4. Fluxo

### 4.1 Extração

`PdfBankTemplate` ganha um método com default (não quebra `NubankExtratoTemplate`):

```java
default YearMonth targetInvoiceReferenceMonth(String fullText) { return null; }
```

`ItauFaturaTemplate`: extrai o `Matcher` de `Vencimento` (hoje já existe, embutido em
`parse()`) para um método privado `extrairVencimento(String fullText)` reusado por `parse()`
(inferência de ano das datas sem ano — comportamento já existente, inalterado) e pelo novo
`targetInvoiceReferenceMonth`, que devolve `YearMonth.of(anoVencimento, mesVencimento).minusMonths(1)`.

`PdfTextExtractor`: ao montar o `NormalizedBatchDTO` no branch onde um template bateu, chama
`template.targetInvoiceReferenceMonth(text)` e preenche os 2 campos novos do DTO (heurística
genérica de linha, sem template, mantém os campos `null`).

### 4.2 Persistência

`ImportService.createBatch`: grava `batch.targetInvoiceReferenceYear()`/`Month()` no builder
do `ImportBatch`, sem lógica adicional.

### 4.3 Commit

`ImportService.commit()`: lê `batch.getTargetInvoiceReferenceYear()`/`Month()` uma vez, fora
do loop de itens. Se não-nulo, cada `TransactionRequestDTO` da iteração é criado através do
NOVO método interno de `TransactionService` (mesma assinatura de hoje + `YearMonth
targetInvoiceMonth` opcional), que substitui o cálculo de `resolveInvoiceMonth(dto.date(),
closingDay)` pelo valor fixo no passo `i=0` do loop de parcelas — os passos seguintes
(`i=1..N-1`, parcelas futuras de um parcelamento novo) continuam cascata normal
(`.plusMonths(i)`) a partir do mês ancorado, sem mudança de fórmula. Se nulo (batch de
CSV/OFX/imagem/heurística), chama a versão existente sem override — comportamento idêntico
ao de hoje, sem exceção especial no código de `commit()`.

## 5. Testes

| Caso | Cobertura |
|---|---|
| `ItauFaturaTemplate.targetInvoiceReferenceMonth` | Texto com `Vencimento: 10/08/2026` → `YearMonth(2026,7)` |
| `TransactionService`, novo método interno | Override presente → parcela âncora usa o mês fixo; parcelas futuras cascata a partir dele |
| `TransactionService`, método público existente | Sem override → comportamento idêntico ao atual (regressão) |
| `ImportService.commit`, batch COM target | Staged com `installment_number=5`, `installment_total=12`, data de compra antiga → transação criada cai na fatura do TARGET, não na fatura calculada pela data antiga |
| `ImportService.commit`, batch SEM target (CSV/OFX/imagem) | Comportamento idêntico ao atual — regressão |
| `PdfTextExtractor` | Template Itaú preenche os 2 campos do `NormalizedBatchDTO`; heurística genérica e Nubank deixam `null` |

**Verificação final (fora do unit test, mesmo padrão desta sessão):** reimportar a fatura de
referência real na conta `Teste Importacao` (dev), confirmar que os 123 lançamentos
(R$15.739,87) caem inteiros numa única fatura (ago/2026) — sem exceção de parcela em
andamento. Limpar a conta de teste antes/depois, como já vem sendo feito.

## 6. Impacto SemVer

**PATCH** — correção de bug, sem mudança de contrato REST (`TransactionRequestDTO` e
`ImportBatchResponseDTO` inalterados nesta entrega).

## 7. Dívida técnica registrada

- **Internacional/produtos e serviços (R$120,66 na fatura de referência):** fora de escopo
  aqui — parsing novo de seções com conversão de moeda. Spec separada.
- **Casamento de parcela `>1/N` contra `InstallmentGroup` existente:** segue fora de escopo
  (já registrado na spec `2026-08-07`) — esta entrega corrige ONDE a transação avulsa cai,
  não elimina a possibilidade de duplicar um grupo já existente no sistema.
- **`referenceMonth = vencimento.minusMonths(1)` assume `dueDay >= closingDay`:** é o caso
  normal do Itaú (confirmado nas 45 faturas medidas), mas não é universal — um cartão com
  `dueDay < closingDay` (fatura vence ANTES do fechamento seguinte, incomum) quebraria essa
  relação. Sem evidência no corpus de que isso ocorra; revisitar se aparecer um caso real.
