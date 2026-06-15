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
  │    ├── FK → Invoice (nullable — só CREDIT_CARD)
  │    └── transferId: UUID (nullable — par INCOME↔EXPENSE de transferências)
  ├── BudgetCycle (startDate, endDate, openingBalance, status: OPEN | CLOSED)
  │    └── BudgetItem (description, amount, type, expectedDate, source, status,
  │                    category?, account?, recurringItem?, transaction?, installmentGroup?)
  ├── RecurringBudgetItem (description, amount, type, dayOfMonth, category?, account?, active)
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
```
