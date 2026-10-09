# Modelo de Domínio

```
Tenant (UUID, budgetCycleStartDay)
  ├── User (email, passwordHash, role: ADMIN | MEMBER, passwordChangedAt?)
  │    └── PasswordResetToken (token, expiresAt, used)  [recuperação de senha; sem tenant_id]
  ├── Account (name, type, color, icon, countInLiquidBalance, countInNetWorth, active)
  │    ├── CreditCardDetails (brand, lastFourDigits, limitAmount, closingDay, dueDay)  [só CREDIT_CARD]
  │    └── Invoice (referenceYear, referenceMonth, closingDate, dueDate, status)        [lazy: 1ª transação do período]
  ├── Category (name, icon, color, parentId?, deletedAt?, taxonomyCode?)
  │    └── Category (filhos — árvore multinível)
  ├── InstallmentGroup (description, totalAmount, totalInstallments, account, category?)
  │    └── Transaction[] (N parcelas vinculadas)
  ├── Transaction (description, amount, date, type, status)
  │    ├── FK → Account
  │    ├── FK → Category (nullable)
  │    ├── FK → InstallmentGroup (nullable)
  │    ├── FK → Invoice (nullable — só CREDIT_CARD; fatura à qual a COMPRA pertence)
  │    ├── FK → Invoice paidInvoice (nullable — este EXPENSE QUITA a fatura; #145)
  │    ├── FK → RecurrenceRule (nullable — materializada de uma regra)
  │    ├── recurrenceOccurrence: LocalDate (nullable — slot canônico da regra)
  │    ├── transferId: UUID (nullable — par INCOME↔EXPENSE de transferências)
  │    └── postingDate: LocalDate (nullable — data de lançamento/fechamento; não consumida até Fase 5)
  ├── RecurrenceRule (description, baseAmount, type, rrule, startDate, status, account, category?)
  │    └── RecurrenceException (occurrenceDate — EXDATE: ocorrência "pulada")
  ├── BudgetCycle (startDate, endDate, openingBalance, status: OPEN | CLOSED)
  │    └── BudgetItem (description, amount, type, expectedDate, source, status,
  │                    category?, account?, recurrenceRule?, recurrenceOccurrenceDate?,
  │                    transaction?, installmentGroup?)
  ├── ImportBatch (importMode, sourceType, extractorUsed, extractorVersion, status, failureReason?,
  │               sourceHash?, sourceFilename?, createdBy)  [fundação da extração]
  │    └── StagedTransaction (fields JSONB {value,confidence} por campo, suggestedCategoryCode?,
  │                           suggestedCategoryConfidence?, overallConfidence?, requiresReview [derivado],
  │                           duplicateCandidateOf?, promotedTransactionId? → Transaction, status)
  └── Invitation (email, token, expiresAt)
```

## Enums

```
AccountType        : CHECKING | CREDIT_CARD | INVESTMENT | CASH
CardBrand          : VISA | MASTERCARD | ELO | AMEX | HIPERCARD
TransactionType    : INCOME | EXPENSE
TransactionStatus  : PENDING | PAID | CANCELLED
InvoiceStatus      : OPEN | CLOSED | PAID
DeleteInstallmentScope : SINGLE | THIS_AND_NEXT | ALL
UserRole           : ADMIN | MEMBER
BudgetCycleStatus  : OPEN | CLOSED
BudgetItemSource   : MANUAL | RECURRING | INSTALLMENT
BudgetItemStatus   : PENDING | REALIZED | SKIPPED
RecurrenceStatus   : ACTIVE | CANCELLED
ImportMode              : NEW_TRANSACTIONS | RECONCILIATION
ImportSourceType        : IMAGE | PDF_TEXT | PDF_SCANNED | CSV | OFX | AUDIO
ImportBatchStatus       : PENDING | EXTRACTED | REVIEWED | COMMITTED | FAILED
StagedTransactionStatus : PENDING | CONFIRMED | DISCARDED
```

## Regras de domínio — faturas e parcelamento

- **Parcela só nasce em fatura aberta (compra parcelada N>1 e recorrência).** Compra parcelada manual e materialização de recorrência criam transação somente quando a fatura de destino existe `OPEN` ou não existe (nasce via lazy create). Fatura `CLOSED`/`PAID` → a parcela é **descartada**: sem transação, sem materializar fatura, sem renumeração (`installmentNumber`/`totalInstallments` preservam os valores pedidos — a parcela já foi paga fora do sistema). **Transação avulsa no cartão (N=1) não descarta** — anexa à fatura mesmo fechada/paga (comportamento histórico). A importação é a exceção: a âncora explícita da fatura-alvo do documento prevalece sobre o status.
- **Invariante `soma(parcelas) == totalAmount` vale apenas sem descarte.** `InstallmentGroup.totalAmount`/`totalInstallments` continuam sendo os valores pedidos na compra (a verdade da compra), mesmo com menos parcelas persistidas.
- **Grupo nasce na primeira parcela efetivamente criada.** Se todas as parcelas forem descartadas, não existe `InstallmentGroup` (nada de grupo órfão sem transações).
- **Recorrência em fatura fechada recusa, não descarta.** Materializar uma ocorrência cuja fatura existe `CLOSED`/`PAID` gera conflito (409) — materializar é ação explícita do usuário, não há "próxima parcela" para pular em silêncio.
- **Preview de parcelamento é somente leitura.** O cálculo de "qual fatura cada parcela cai e se será criada" existe como endpoint read-only (`willCreate` por parcela), sem efeito colateral — contrato em `summary.md` e `api-spec/openapi.yaml`.
