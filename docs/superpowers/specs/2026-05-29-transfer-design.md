# Design: Transferência entre Contas (Issue #23)

**Data:** 2026-05-29  
**Status:** Aprovado  
**Referência:** [Issue #23](https://github.com/sergiohcosta/fintech-core/issues/23)

---

## Contexto

O sistema já tem infra de double-entry para transferências:
- `Transaction.transferId` (UUID) vincula as duas pernas de uma transferência
- `TransactionService.createTransfer()` já existe mas não está exposto via HTTP
- `TransactionResponseDTO.transferId` já é retornado na listagem
- `transaction-list.ts` já tem label `TRANSFER` no `typeLabel()`

O trabalho principal é expor o endpoint, criar o DTO, ajustar o OpenAPI e criar o formulário no Angular.

---

## Decisões de Design

| Decisão | Escolha | Motivo |
|---------|---------|--------|
| Tipo no banco | Manter INCOME/EXPENSE + `transferId` | Sem migration, cálculo de saldo permanece correto |
| Endpoint de criação | `POST /api/transfers` (controller dedicado) | Separa responsabilidades; escala melhor |
| Endpoint de exclusão | `DELETE /api/transfers/{transferId}` | Semântica clara: exclui a transferência, não uma transação |
| UX de acesso | Toggle `MatButtonToggle` no `TransactionForm` | Sem nova rota, sem novo componente; padrão de apps financeiros BR |
| Descrição | Campo opcional, default `"Transferência"` | Permite descrever motivo sem obrigar |
| Edição | Bloqueada — transferências não são editáveis | Integridade das duas pernas |
| Exclusão | Atômica — exclui as duas pernas via `transferId` | Consistência double-entry |

---

## Backend

### Novos artefatos

#### `TransferRequestDTO`
```java
record TransferRequestDTO(
    @NotNull UUID fromAccountId,
    @NotNull UUID toAccountId,
    @NotNull @DecimalMin("0.01") BigDecimal amount,
    @NotNull LocalDate date,
    String description   // opcional; default "Transferência" no service
)
```

Validação de negócio no service: `fromAccountId != toAccountId`; se iguais, lança `IllegalArgumentException` → `GlobalExceptionHandler` retorna 400.

#### `TransferResponseDTO`
```java
record TransferResponseDTO(
    UUID transferId,
    UUID fromLegId,
    UUID toLegId,
    BigDecimal amount,
    LocalDate date,
    String description,
    String fromAccount,
    String toAccount
)
```

#### `TransferController`

`@RequestMapping("/api/transfers")`

| Método | Path | Response |
|--------|------|----------|
| `POST` | `/api/transfers` | 201 + `TransferResponseDTO` |
| `DELETE` | `/api/transfers/{transferId}` | 204 |

#### Ajustes em `TransactionService`

- `createTransfer(TransferRequestDTO dto, User user)` — refatora assinatura atual para receber DTO
- `deleteTransfer(UUID transferId, User user)` — novo método; busca pernas por `transferId + tenant`, deleta ambas em `@Transactional`

#### Ajuste em `TransactionRepository`

```java
List<Transaction> findByTransferIdAndTenant(UUID transferId, Tenant tenant);
```

#### OpenAPI (`openapi.yaml`)

Dois novos paths:
- `POST /api/transfers` com schema `TransferRequest` / `TransferResponse`
- `DELETE /api/transfers/{transferId}` com response 204

---

## Frontend

### Toggle no `TransactionForm`

`MatButtonToggle` no topo do formulário com valores `TRANSACTION | TRANSFER`.  
Estado: `mode = signal<'TRANSACTION' | 'TRANSFER'>('TRANSACTION')`.

**Modo TRANSACTION** (comportamento atual, sem alteração):
```
Descrição | Valor | Data | Tipo | Status | Parcelas | Categoria | Conta
```

**Modo TRANSFER** (campos trocados via `@if`):
```
Descrição (opcional) | Valor | Data | Conta origem | Conta destino
```

Validação cross-field: `fromAccountId === toAccountId` → erro `"Contas devem ser diferentes"`.

O modo de edição (`/transactions/:id`) não exibe o toggle — permanece somente TRANSACTION.

### `onSubmit()` — lógica de despacho

```
if (mode === TRANSFER)
  → TransfersService.createTransfer(payload)
  → navega para /transactions
else
  → comportamento atual
```

### `transaction-list` — ações condicionais

Quando `t.transferId != null`:
- Botão **Editar** → desabilitado com tooltip `"Transferências não podem ser editadas"`
- Botão **Excluir** → chama `DELETE /api/transfers/{t.transferId}`, recarrega a lista
- Coluna **Tipo** → exibe `"Transferência"` (lógica já em `typeLabel()`, usa `transferId != null`)

### Novos artefatos

| Artefato | Origem |
|----------|--------|
| `core/api/transfers/transfers.service.ts` | Gerado pelo Orval após atualizar OpenAPI |
| Campos `fromAccountId`, `toAccountId` no `FormGroup` | Adicionados ao `TransactionForm` existente |
| `mode` signal | Adicionado ao `TransactionForm` |
| Ajuste em `transaction-list.html/.ts` | Ações condicionadas a `transferId` |

Sem nova rota. Sem novo componente de página.

---

## Testes

### Backend — `TransactionServiceTest`

| Cenário | Expectativa |
|---------|-------------|
| `createTransfer` com contas válidas | 2 transactions salvas com mesmo `transferId`; uma EXPENSE, uma INCOME |
| `createTransfer` com `fromAccount == toAccount` | `IllegalArgumentException` |
| `createTransfer` com conta de outro tenant | `EntityNotFoundException` |
| `deleteTransfer` com `transferId` válido | 2 registros deletados |
| `deleteTransfer` com `transferId` de outro tenant | `EntityNotFoundException` |

### Backend — `TransferControllerTest`

| Cenário | Expectativa |
|---------|-------------|
| `POST /api/transfers` payload válido | 201 + body com `transferId`, `fromLegId`, `toLegId` |
| `POST /api/transfers` contas iguais | 400 |
| `POST /api/transfers` sem autenticação | 403 |
| `DELETE /api/transfers/{transferId}` válido | 204 |
| `DELETE /api/transfers/{transferId}` inexistente | 404 |

### Frontend — `transaction-form.spec.ts`

| Cenário | Expectativa |
|---------|-------------|
| Toggle → TRANSFER | Campos `fromAccountId`/`toAccountId` visíveis; `type`, `category` ocultos |
| Submit em TRANSFER com contas iguais | Erro cross-field exibido |
| Submit em TRANSFER válido | Chama `TransfersService.createTransfer()`, não `TransactionsService` |
| Modo edição | Toggle não exibido |

---

## O que não muda

- `TransactionType` enum — sem alteração, sem migration
- Rotas do frontend — sem nova rota
- `TransactionController` — sem alteração
- Lógica de saldo/dashboard — transferências continuam sendo INCOME + EXPENSE
