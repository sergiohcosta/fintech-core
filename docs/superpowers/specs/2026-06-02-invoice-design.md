# Design: Modelo de Fatura (Invoice) para Cartão de Crédito

**Data:** 2026-06-02  
**Status:** Aprovado

---

## Contexto

O sistema atual representa parcelas como transações com `date = purchaseDate + i meses` — uma aproximação que ignora o `closingDay` do cartão. Isso causa dois problemas:

1. Parcelas aparecem no mês errado no dashboard quando a compra ocorre após o dia de fechamento.
2. Não há agrupamento de transações por fatura, impossibilitando o ciclo de pagamento (OPEN → CLOSED → PAID).

`CreditCardDetails` já possui `closingDay` e `dueDay`. O que falta é a entidade `Invoice` e a lógica de atribuição.

---

## Escopo

- **Backend:** entidade Invoice, algoritmo de atribuição, API REST, ajuste no DashboardService
- **Frontend (mínimo):** preview de parcelas com label de fatura no formulário; chip informativo na listagem de transações
- **Fora do escopo:** tela de gestão de faturas, ação de fechar/pagar no frontend (próxima iteração)

---

## Modelo de Dados

### Tabela `invoices`

```sql
CREATE TABLE invoices (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id       UUID NOT NULL REFERENCES accounts(id),
    tenant_id        UUID NOT NULL REFERENCES tenants(id),
    reference_year   INT  NOT NULL,
    reference_month  INT  NOT NULL,   -- 1-12
    closing_date     DATE NOT NULL,   -- frozen no momento da criação
    due_date         DATE NOT NULL,   -- frozen no momento da criação
    status           VARCHAR(10) NOT NULL DEFAULT 'OPEN',
    created_at       TIMESTAMP NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (account_id, reference_year, reference_month)
);
```

`closing_date` e `due_date` são gravadas na criação e nunca recalculadas. Mudanças futuras no `closingDay` do cartão não afetam faturas já existentes.

### Mudança em `transactions`

```sql
ALTER TABLE transactions ADD COLUMN invoice_id UUID REFERENCES invoices(id);
```

Nullable — apenas transações em conta `CREDIT_CARD` recebem o vínculo.

### Semântica de `Transaction.date`

`Transaction.date` permanece como **data de compra** (o que o usuário digitou). Para cartão de crédito, o agrupamento mensal no dashboard passa a usar `invoice.due_date`. Isso preserva o registro histórico preciso da compra sem falsificar a data.

---

## Lógica de Negócio

### Algoritmo de atribuição de fatura

Dado `purchaseDate` e `closingDay`:

```
se purchaseDate.dayOfMonth <= closingDay:
    referenceMonth = mês de purchaseDate
senão:
    referenceMonth = mês de purchaseDate + 1
```

Cálculo do `due_date`:

```
se dueDay >= closingDay → vencimento no mesmo mês do fechamento
se dueDay < closingDay  → vencimento no mês seguinte ao fechamento
```

Exemplos com `closingDay=5`, `dueDay=15`:

| Data da compra | Referência    | Fechamento | Vencimento |
|----------------|---------------|------------|------------|
| 03/dez         | Fatura Dez    | 05/dez     | 15/dez     |
| 06/dez         | Fatura Jan    | 05/jan     | 15/jan     |
| 05/dez         | Fatura Dez    | 05/dez     | 15/dez     |

### Criação lazy

`InvoiceService.getOrCreate(account, year, month)`:
- Busca por `(account_id, reference_year, reference_month)`
- Se não existe: calcula `closing_date` e `due_date`, persiste com `OPEN`
- Se existe: retorna sem alterar status

### Parcelamentos

Para uma compra parcelada em N vezes:
- Parcela `i` (0-indexed) é atribuída à fatura do mês `referenceMonth + i`
- `Transaction.date` é igual à data de compra em todas as parcelas
- Cada parcela recebe `invoice_id` da sua fatura correspondente

### Transações avulsas em cartão

Seguem o mesmo algoritmo — `purchaseDate` determina a fatura via `closingDay`.

### Ciclo de vida do status

```
OPEN → CLOSED → PAID
```

Unidirecional. Transições são ações explícitas do usuário via API.

### Comportamento retroativo

Backend aceita lançamentos em faturas CLOSED ou PAID sem erro. O status da fatura é retornado no `TransactionResponseDTO` e no `InvoiceResponseDTO`; o frontend decide como alertar o usuário:
- CLOSED: aviso leve
- PAID: aviso mais forte ("fatura já quitada")

---

## API

### `InvoiceController` — novos endpoints

| Método | Path | Descrição |
|--------|------|-----------|
| `GET` | `/api/invoices?accountId={id}` | Lista faturas do cartão (paginado) |
| `GET` | `/api/invoices/{id}` | Detalhe de uma fatura |
| `POST` | `/api/invoices/{id}/close` | OPEN → CLOSED |
| `POST` | `/api/invoices/{id}/pay` | CLOSED → PAID |

Transações de uma fatura são obtidas via endpoint existente com filtro adicional:
`GET /api/transactions?invoiceId={id}` — reutiliza a infraestrutura de listagem sem duplicar responsabilidade.

Todas validam que `account` pertence ao tenant do usuário autenticado.

### `InvoiceResponseDTO`

```json
{
  "id": "uuid",
  "accountId": "uuid",
  "accountName": "Nubank",
  "referenceMonth": 1,
  "referenceYear": 2027,
  "label": "Janeiro/2027",
  "closingDate": "2027-01-05",
  "dueDate": "2027-01-15",
  "status": "OPEN",
  "totalAmount": 1250.00,
  "transactionCount": 8
}
```

`totalAmount` e `transactionCount` são agregados em query — não armazenados.

### Mudanças em endpoints existentes

**`POST /api/transactions`**: quando `account.type == CREDIT_CARD`, chama `InvoiceService.getOrCreate()` e atribui `invoice_id` à(s) transação(ões).

**`GET /api/transactions?invoiceId={id}`** — novo filtro opcional que retorna apenas as transações da fatura especificada.

**`GET /api/transactions`** — `TransactionResponseDTO` ganha campos nullable:
```json
{
  "invoiceId": "uuid",
  "invoiceDueDate": "2026-12-15",
  "invoiceStatus": "OPEN"
}
```

**`GET /api/dashboard/summary`**: `DashboardService` usa `invoice.due_date` para agrupar transações de cartão de crédito por mês, em vez de `transaction.date`.

---

## Frontend (mínimo)

### Formulário de transação — preview de parcelas

Quando `account.type == CREDIT_CARD`, o preview calcula o label de fatura localmente (sem chamada extra à API) usando `closingDay` e `dueDay` do `CreditCardDetailsResponseDTO`:

```
1/3 · Fatura Jun/2026 · vence 15/06 · R$ 166,67
2/3 · Fatura Jul/2026 · vence 15/07 · R$ 166,67
3/3 · Fatura Ago/2026 · vence 15/08 · R$ 166,67
```

Para compra avulsa em cartão, exibe a fatura que receberá o lançamento:
```
Conta: Nubank · Fatura Jun/2026 · vence 15/06
```

### Listagem de transações — chip informativo

Transações com `invoiceId` exibem um chip discreto:
- Cinza: OPEN
- Amarelo: CLOSED
- Verde: PAID

Nenhuma ação no chip — apenas informativo.

---

## Estrutura de arquivos afetados

### Backend (novos)
- `domain/invoice/Invoice.java`
- `domain/enums/InvoiceStatus.java`
- `repository/InvoiceRepository.java`
- `service/InvoiceService.java`
- `controller/InvoiceController.java`
- `dto/invoice/InvoiceResponseDTO.java`
- `dto/invoice/InvoiceListResponseDTO.java`
- `resources/db/migration/V9__invoices.sql`

### Backend (alterados)
- `service/TransactionService.java` — chama InvoiceService ao criar em CREDIT_CARD
- `dto/transaction/TransactionResponseDTO.java` — adiciona invoiceId, invoiceDueDate, invoiceStatus
- `service/DashboardService.java` — usa due_date para cartão no agrupamento mensal

### Frontend (alterados)
- `transaction-form` — preview com label de fatura para CREDIT_CARD
- `transaction-list` — chip informativo de fatura
- Geração de cliente via Orval após atualizar o OpenAPI spec

---

## Decisões registradas

| Questão | Decisão | Motivo |
|---------|---------|--------|
| Escopo de transactions | Todas as CREDIT_CARD, não só parceladas | Modelo correto; avulsas também pertencem a faturas |
| Status de Invoice | OPEN → CLOSED → PAID | Ciclo completo necessário para gestão real |
| Criação de Invoice | Lazy (on-demand) | Sem complexidade de geração antecipada ou jobs |
| Retroativo em CLOSED/PAID | Permitido com aviso no frontend | Finanças pessoais precisam ser forgiving |
| Transaction.date | Permanece como data de compra | Preserva registro histórico; dashboard usa invoice.due_date |
| Frontend | Mínimo — preview + chip | Tela de gestão é feature separada |
