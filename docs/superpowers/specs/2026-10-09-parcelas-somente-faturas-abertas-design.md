# Spec: Parcelas somente em faturas abertas

**Data:** 2026-10-09
**Status:** proposto (aguardando aprovação do dev)

## Contexto

Ao lançar uma compra parcelada com **data no passado**, cada parcela é roteada para a fatura
calculada aritmeticamente (`resolveInvoiceMonth(dataCompra, closingDay) + i`), **sem nenhuma
checagem de status nem da data corrente**. Como `InvoiceService.getOrCreate` devolve a fatura
existente sem olhar o status, hoje uma parcela pode ser anexada a uma fatura já `CLOSED` ou
`PAID`. Consequências verificadas no código:

| Efeito | Evidência |
|---|---|
| Total da fatura **infla** (a soma inclui `PENDING`, só exclui `CANCELLED`) | `TransactionRepository.sumAmountByInvoice`; `InvoiceService.buildDTO` |
| A parcela fica `PENDING` **presa para sempre** numa fatura `PAID` (o único batch `PENDING→PAID` roda dentro do `pay()`, que não reexecuta) | `InvoiceService.pay` |
| Ela **continua contando como dívida** no saldo do cartão (cartão soma `PENDING`) | `AccountService.toResponse` (#198) |

Os três caminhos que resolvem fatura hoje: `TransactionService.create` manual (2 args),
`create` com âncora de importação (3 args) e `TransactionService.materializeFromRule`
(recorrência, que monta a `Transaction` direto). Não há teste cobrindo a sequência
"fatura `PAID`/`CLOSED` → `create` com data que cai nela".

**Objetivo:** compra parcelada manual e materialização de recorrência criam transações
**somente em faturas abertas**; parcelas que cairiam em fatura `CLOSED`/`PAID` são
**descartadas** (não criadas, sem renumerar). A importação mantém a âncora explícita do
documento. O frontend confirma com o usuário antes de gravar.

## Comportamento alvo

Para cada parcela `i` (0..N-1), com mês natural `resolveInvoiceMonth(dataCompra, closingDay) + i`:

- Fatura **não existe** → `getOrCreate` (nasce `OPEN`) e a parcela é criada com
  `installmentNumber = i + 1`;
- Fatura existe `OPEN` → parcela criada;
- Fatura existe `CLOSED`/`PAID` → parcela **descartada** (não cria transação, não cria fatura,
  não renumera).

Exemplo: compra 08/07/2026, cartão fecha dia 2 / vence dia 10, hoje 09/10/2026, em 6x:

| Parcela | Mês natural | Status hoje | Resultado |
|---|---|---|---|
| 1 | jul/2026 | `PAID` | descartada |
| 2 | ago/2026 | `CLOSED` | descartada |
| 3 | set/2026 | `OPEN` | criada — rótulo `3/6` |
| 4 | out/2026 | não existe → nasce `OPEN` | criada — `4/6` |
| 5 | nov/2026 | — | criada — `5/6` |
| 6 | dez/2026 | — | criada — `6/6` |

Regra válida para as duas rotas com `skipClosedInvoices = true`: manual e recorrência.
A importação (`create(dto, user, anchor)`) permanece inalterada (`skipClosedInvoices = false`).

## Decisões

- **D1 — Ponto único com flag explícito.** Extrair em `TransactionService` um caminho privado
  `create(dto, user, anchor, skipClosedInvoices)`; a sobrecarga pública de 2 args (manual) chama
  com `(null, true)`; a de 3 args (importação/testes) chama com `(anchor, false)`.
  *Alternativa descartada — chavear por `anchor == null`:* a importação sem fatura-alvo no
  documento passa `null` e mudaria de comportamento sem intenção.

- **D2 — Definição de "fatura aberta".** Existe com `status == OPEN`, **ou** não existe (o
  `getOrCreate` a cria `OPEN`). `CLOSED`/`PAID` → descarta. O descarte **não** chama `getOrCreate`
  (não materializa fatura).

- **D3 — `InstallmentGroup` mantém a verdade da compra.** `totalAmount` e `totalInstallments`
  continuam os valores pedidos (N), mesmo com menos transações persistidas. Para a UI não ficar
  inconsistente, `InstallmentGroupResponseDTO` ganha `skippedInstallments`
  (`= totalInstallments − quantidade de transações existentes`).
  *Alternativa descartada — reduzir `totalInstallments` do grupo ao número criado:* o rótulo
  `3/6` brigaria com um total de 4 parcelas.

- **D4 — Todos descartados.** `create` retorna lista vazia e **não** cria `InstallmentGroup`
  (grupo é criado na primeira parcela efetivamente criada).

- **D5 — Preview server-side.** Novo endpoint `POST /api/transactions/installment-preview`
  (read-only, sem `getOrCreate`) devolve, por parcela, mês/ano da fatura, vencimento, status
  atual e `willCreate`. O frontend usa isso para montar o diálogo; o backend continua sendo a
  autoridade na gravação.
  *Alternativa descartada — preview client-side com `listInvoices` + `installment-preview.ts`:*
  reimplementa `resolveInvoiceMonth` no TS (duplicação já existente e sujeita a divergir) e
  alarga a janela de corrida entre preview e submit.

- **D6 — Recorrência em fatura fechada.** `materializeFromRule` lança
  `BusinessConflictException` (409) quando a fatura da ocorrência existe `CLOSED`/`PAID`.
  A materialização é ação explícita do usuário; não faz sentido "não gravar em silêncio".
  *Alternativa descartada — descartar silenciosamente.*

## Backend — desenho

**`InvoiceService`** — novo método de leitura (sem `getOrCreate`):

```java
@Transactional(readOnly = true)
public Optional<Invoice> findExisting(Account account, int referenceYear, int referenceMonth) {
    return repository.findByAccountAndReferenceYearAndReferenceMonth(account, referenceYear, referenceMonth);
}
```

Extrair de `createNewInvoice` o cálculo de datas para reuso no preview:

```java
// já existe atDayCapped (privado). Extrair o par (closingDate, dueDate) para um helper reutilizável.
static InvoiceSchedule scheduleFor(int referenceYear, int referenceMonth, int closingDay, int dueDay);
```

**`TransactionService.create`** — reestruturado:

```java
public List<TransactionResponseDTO> create(TransactionRequestDTO dto, User user) {
    return create(dto, user, null, true);          // manual: descarta parcelas em fatura fechada
}
public List<TransactionResponseDTO> create(TransactionRequestDTO dto, User user, YearMonth anchor) {
    return create(dto, user, anchor, false);       // importação: mantém âncora explícita
}
private List<TransactionResponseDTO> create(TransactionRequestDTO dto, User user,
                                            YearMonth anchor, boolean skipClosedInvoices) { ... }
```

Dentro do loop:

```java
if (isCreditCard) {
    YearMonth invoiceMonth = (anchor != null ? anchor : resolveInvoiceMonth(dto.date(), closingDay)).plusMonths(i);
    if (skipClosedInvoices) {
        Invoice existing = invoiceService.findExisting(account, invoiceMonth.getYear(), invoiceMonth.getMonthValue()).orElse(null);
        if (existing != null && existing.getStatus() != InvoiceStatus.OPEN) {
            continue; // parcela descartada — não cria transação nem fatura
        }
    }
    invoice = invoiceService.getOrCreate(account, invoiceMonth.getYear(), invoiceMonth.getMonthValue());
    transactionDate = dto.date();
}
// grupo criado lazy, na primeira parcela efetivamente criada (D4)
```

**`TransactionService.materializeFromRule`** — antes do `getOrCreate`:

```java
if (AccountType.CREDIT_CARD.equals(account.getType())) {
    ...
    YearMonth invoiceMonth = resolveInvoiceMonth(date, closingDay);
    Invoice existing = invoiceService.findExisting(account, invoiceMonth.getYear(), invoiceMonth.getMonthValue()).orElse(null);
    if (existing != null && existing.getStatus() != InvoiceStatus.OPEN) {
        throw new BusinessConflictException("A fatura de " + invoiceMonth + " está "
            + existing.getStatus() + "; não é possível lançar nesta ocorrência.");
    }
    invoice = invoiceService.getOrCreate(...);
}
```

**Preview** — `TransactionService.previewInstallments(dto, user)` read-only, reusando
`resolveInvoiceMonth` + `scheduleFor` + `findExisting`; valida que a conta é `CREDIT_CARD`
(caso contrário, 400 `BusinessException`). Endpoint em `TransactionController` (mesma classe,
`POST /installment-preview`, autenticado — não há regra de role específica; segurança é
`anyRequest().authenticated()`).

DTOs novos:

```java
public record InstallmentPreviewRequestDTO(
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotNull LocalDate date,
        Integer totalInstallments,
        @NotNull UUID accountId) {}

public record InstallmentPreviewDTO(
        int installmentNumber, int totalInstallments, BigDecimal amount,
        int referenceYear, int referenceMonth, LocalDate closingDate, LocalDate dueDate,
        UUID invoiceId, InvoiceStatus invoiceStatus, boolean willCreate) {}
```

## Frontend — desenho

`transaction-form` (cartão + parcelado): no submit, antes de gravar, chamar
`installmentPreview`. Se existir alguma linha com `willCreate == false`, abrir um **diálogo de
confirmação** (novo componente standalone, `MatDialog` + Signals/Zoneless) que lista as parcelas:
número, valor, fatura (mês/ano), vencimento e "criada"/"ignorada (fatura fechada/paga)". No
confirmar → `createTransaction`; no cancelar → não grava. Sem descartes → grava direto (sem
diálogo). Erro 409 vindo da recorrência é exibido como mensagem (snackbar) no fluxo de
confirmação de ocorrência.

O componente visual é responsabilidade do `@designer`; o detalhamento da tela não faz parte
desta spec além do contrato acima.

## Teste

- `TransactionServiceTest`: (a) manual com fatura-âncora `PAID` → parcela descartada, sem
  `getOrCreate` para aquele mês, sem transação; (b) `CLOSED` idem; (c) numeração preservada
  (`3/6..6/6`) quando 1 e 2 são descartadas; (d) todos descartados → lista vazia e nenhum
  `InstallmentGroup` salvo; (e) sem descartes → comportamento atual (ajustar o teste do
  invariante `soma == total`, linha ~103, para o caso sem descarte); (f) conta não-cartão
  inalterada; (g) sobrecarga de 3 args (importação) **não** descarta (regressão).
- `TransactionServiceTest`/`RecurrenceRuleServiceTest`: `materializeFromRule` com fatura
  `CLOSED`/`PAID` → `BusinessConflictException`.
- `InvoiceServiceTest`: `findExisting` e o helper `scheduleFor`.
- `TransactionControllerTest`: `POST /installment-preview` (200 e shape; 400 para conta não-cartão).
- Frontend: cálculo puro do "há descartes?" e renderização do diálogo; `transaction-form`
  chama preview e só grava após confirmação.

## Fora de escopo

- Alterar o comportamento da importação (mantém âncora explícita).
- Reabrir/reativar fatura `CLOSED`/`PAID`.
- Backfill/correção de parcelas já presas em faturas pagas (bug latente existente).
- Fazer `close()` bloquear novas transações (a fatura `CLOSED` continua aceitando atrasadas
  **fora** deste fluxo manual/recorrência).
- Coluna materializada `effective_date` / paginação server-side (#85).

## Impacto SemVer

**MINOR** — novo endpoint de preview e novo campo `skippedInstallments` no contrato
(`api-spec/openapi.yaml`); fronteira de compatibilidade preservada (adições).
