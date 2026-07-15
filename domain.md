# Modelo de Domínio

```
Tenant (UUID, budgetCycleStartDay)
  ├── User (email, passwordHash, role: ADMIN | MEMBER)
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
  │    └── transferId: UUID (nullable — par INCOME↔EXPENSE de transferências)
  ├── RecurrenceRule (description, baseAmount, type, rrule, startDate, status, account, category?)
  │    └── RecurrenceException (occurrenceDate — EXDATE: ocorrência "pulada")
  ├── BudgetCycle (startDate, endDate, openingBalance, status: OPEN | CLOSED)
  │    └── BudgetItem (description, amount, type, expectedDate, source, status,
  │                    category?, account?, recurrenceRule?, recurrenceOccurrenceDate?,
  │                    transaction?, installmentGroup?)
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
```
