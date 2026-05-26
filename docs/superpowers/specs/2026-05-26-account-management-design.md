# Design Spec: Gerenciamento de Contas

**Data:** 2026-05-26  
**Status:** Aprovado  
**Feature:** Account Management (Gerenciamento de Contas)

---

## 1. Contexto e Motivação

O modelo atual do projeto referencia `CreditCard` diretamente nas transações, sem nenhum conceito de conta bancária. Isso impede qualquer cálculo real de saldo disponível, previsão financeira ou rastreamento de patrimônio.

Esta feature introduz a entidade `Account` como o centro gravitacional do sistema financeiro — toda entidade capaz de ter saldo (conta corrente, poupança, investimento, cartão de crédito, carteira física) passa a ser uma conta, com tipos bem definidos e flags de comportamento.

---

## 2. Decisões Arquiteturais

| Dimensão | Decisão | Justificativa |
|---|---|---|
| Modelagem | Composição: `Account` + tabela satélite `credit_card_details` | Tabela central estável; campos específicos isolados por tipo; escalável para novos tipos sem alterar a tabela core |
| Saldo | Calculado via `SUM(transactions)` — sem coluna `balance` | YAGNI: o volume desta fase não justifica a complexidade de manter saldo armazenado consistente; refatoração B→A é direta quando necessário |
| Transferências | Partidas dobradas: 2 transações espelhadas com `transfer_id` compartilhado | Padrão da indústria; mantém o saldo calculado simples e o histórico de cada conta limpo |
| Migração | Flyway atômico: converte `credit_cards` existentes em `accounts` + `credit_card_details` | Sem perda de dados; schema nunca fica em estado inconsistente |
| Contrato API | Spec-first: `api-spec/openapi.yaml` é alterado antes do código Java e do Orval | Padrão já adotado no projeto desde a migração OpenAPI |
| Desenvolvimento | Multi-agente com TDD; cada agente na sua branch; merge em `develop`; `main` via MR | Paralelismo seguro com rastreabilidade e revisão antes de promover |

---

## 3. Tipos de Conta (V1)

| Tipo | Enum | Saldo Líquido | Patrimônio | Tabela Satélite |
|---|---|---|---|---|
| Conta Corrente | `CHECKING` | ✅ Sim | ✅ Sim | Não |
| Investimento | `INVESTMENT` | ❌ Não | ✅ Sim | Não |
| Cartão de Crédito | `CREDIT_CARD` | ❌ Não | ✅ Sim (passivo) | `credit_card_details` |
| Carteira Física | `CASH` | ✅ Sim | ✅ Sim | Não |

As flags `countInLiquidBalance` e `countInNetWorth` têm defaults inteligentes por tipo na criação, mas o usuário pode sobrescrever individualmente.

---

## 4. Modelo de Dados

### 4.1 Tabela `accounts`

```sql
CREATE TABLE accounts (
    id                      UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    name                    VARCHAR(100) NOT NULL,
    type                    VARCHAR(20)  NOT NULL,  -- AccountType enum
    color                   VARCHAR(7),
    icon                    VARCHAR(50),
    count_in_liquid_balance BOOLEAN NOT NULL DEFAULT true,
    count_in_net_worth      BOOLEAN NOT NULL DEFAULT true,
    is_active               BOOLEAN NOT NULL DEFAULT true,
    tenant_id               UUID NOT NULL REFERENCES tenants(id),
    created_by              UUID REFERENCES users(id),
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_accounts_tenant ON accounts(tenant_id);
CREATE INDEX idx_accounts_tenant_type ON accounts(tenant_id, type);
```

### 4.2 Tabela `credit_card_details`

```sql
CREATE TABLE credit_card_details (
    id               UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    account_id       UUID NOT NULL UNIQUE REFERENCES accounts(id) ON DELETE CASCADE,
    brand            VARCHAR(20),
    last_four_digits VARCHAR(4),
    limit_amount     DECIMAL(19, 2),
    closing_day      INTEGER,
    due_day          INTEGER
);
```

### 4.3 Tabela `transactions` — alterações

```sql
-- Adicionar
ALTER TABLE transactions ADD COLUMN account_id UUID REFERENCES accounts(id);
ALTER TABLE transactions ADD COLUMN transfer_id UUID;

-- Migrar dados
UPDATE transactions SET account_id = credit_card_id WHERE credit_card_id IS NOT NULL;

-- Tornar obrigatório e remover antigo
ALTER TABLE transactions ALTER COLUMN account_id SET NOT NULL;
ALTER TABLE transactions DROP COLUMN credit_card_id;
```

> **Nota:** `TransactionType.TRANSFER` deixa de existir. Com partidas dobradas, transferências são representadas por dois registros `INCOME`/`EXPENSE` vinculados pelo `transfer_id`. O enum `TransactionType` passa a ter apenas `INCOME` e `EXPENSE`.

---

## 5. Arquitetura Backend

### 5.1 Camadas

```
AccountController  (implementa interface gerada pelo OpenAPI Generator)
    └── AccountService
            ├── AccountRepository
            └── CreditCardDetailsRepository

TransactionService  (atualizado)
    └── createTransfer(fromAccountId, toAccountId, amount, date)
            └── persiste 2 transações na mesma @Transactional
```

### 5.2 Entidades JPA

**`Account`**
```java
@Entity @Table(name = "accounts")
public class Account {
    UUID id;
    String name;
    AccountType type;           // enum: CHECKING, INVESTMENT, CREDIT_CARD, CASH
    String color, icon;
    boolean countInLiquidBalance;
    boolean countInNetWorth;
    boolean isActive;
    Tenant tenant;
    User createdBy;
    LocalDateTime createdAt, updatedAt;
    // Sem campo balance — calculado sob demanda via SUM(transactions)
}
```

**`CreditCardDetails`**
```java
@Entity @Table(name = "credit_card_details")
public class CreditCardDetails {
    UUID id;
    @OneToOne Account account;
    CardBrand brand;
    String lastFourDigits;
    BigDecimal limitAmount;
    Integer closingDay, dueDay;
}
```

**`Transaction`** — campo alterado:
```java
// Remove: CreditCard creditCard
// Adiciona:
@ManyToOne Account account;    // obrigatório
UUID transferId;               // nullable — presente nas duas pernas de uma transferência
```

### 5.3 Cálculo de Saldo

```java
// AccountRepository
@Query("""
    SELECT COALESCE(SUM(
        CASE WHEN t.type = 'INCOME' THEN t.amount ELSE -t.amount END
    ), 0)
    FROM Transaction t
    WHERE t.account = :account
      AND t.status <> 'CANCELLED'
""")
BigDecimal calculateBalance(Account account);
```

### 5.4 Fluxo de Transferência

```java
@Transactional
public void createTransfer(UUID fromId, UUID toId, BigDecimal amount, LocalDate date) {
    UUID transferId = UUID.randomUUID();
    Account from = accountRepository.findByIdAndTenant(fromId, tenant);
    Account to   = accountRepository.findByIdAndTenant(toId, tenant);

    transactionRepository.save(Transaction.builder()
        .type(EXPENSE).amount(amount).account(from)
        .transferId(transferId).date(date).build());

    transactionRepository.save(Transaction.builder()
        .type(INCOME).amount(amount).account(to)
        .transferId(transferId).date(date).build());
}
```

O `@Transactional` garante que as duas transações são persistidas como unidade atômica — ou as duas, ou nenhuma.

---

## 6. Contrato OpenAPI

Seguindo spec-first: o `api-spec/openapi.yaml` é alterado primeiro.

### 6.1 Tag `credit-cards` → removida

Substituída integralmente pela tag `accounts`.

### 6.2 Novos endpoints (tag `accounts`)

```
GET    /api/accounts          — lista todas as contas ativas do tenant (com saldo calculado)
POST   /api/accounts          — cria nova conta
GET    /api/accounts/{id}     — detalhe completo com saldo + creditCardDetails se aplicável
PUT    /api/accounts/{id}     — atualiza nome, cor, icon, flags
DELETE /api/accounts/{id}     — soft-delete (is_active = false)
```

### 6.3 Defaults de flags por tipo

| Tipo | `countInLiquidBalance` | `countInNetWorth` |
|---|---|---|
| `CHECKING` | `true` | `true` |
| `INVESTMENT` | `false` | `true` |
| `CREDIT_CARD` | `false` | `true` |
| `CASH` | `true` | `true` |

O backend aplica esses defaults no `AccountService.create()` caso o campo não seja informado na requisição. O usuário pode sobrescrever.

### 6.4 Novos schemas

```yaml
AccountType:
  enum: [CHECKING, INVESTMENT, CREDIT_CARD, CASH]

CreditCardDetailsRequest:
  properties:
    brand, lastFourDigits, limitAmount, closingDay, dueDay

AccountCreateRequest:
  required: [name, type]
  properties:
    name, type (AccountType), color, icon,
    countInLiquidBalance (boolean, default por tipo),
    countInNetWorth (boolean, default por tipo),
    creditCardDetails (CreditCardDetailsRequest, opcional)

AccountResponse:
  required: [id, name, type, countInLiquidBalance, countInNetWorth, isActive, balance]
  properties:
    id, name, type, color, icon,
    countInLiquidBalance, countInNetWorth, isActive,
    balance (BigDecimal — calculado),
    creditCardDetails (CreditCardDetailsResponse, nullable)
```

### 6.5 Atualização na tag `transactions`

```yaml
TransactionRequest:
  # Remove: creditCardId
  # Adiciona: accountId (required)

TransactionResponse:
  # Remove: creditCardId
  # Adiciona: accountId (required), transferId (nullable)
```

---

## 7. Escopo Frontend

### 7.1 Nova feature `features/account/`

```
features/account/
  account-list/    — tabela: nome, tipo (chip), saldo, flags, ações (editar/arquivar)
  account-form/    — formulário criação/edição
                     campos de cartão (brand, lastFourDigits, limit, closingDay, dueDay)
                     aparecem condicionalmente quando type = CREDIT_CARD
```

O Orval gera `core/api/accounts/accounts.service.ts` automaticamente a partir da tag `accounts`.

### 7.2 Impacto em features existentes

- **`transaction-form`**: seletor `creditCard` → seletor `account`
- **`dashboard`**: sem mudança de escopo em V1

---

## 8. Estratégia de Migração Flyway (V5)

Uma única migration SQL executada atomicamente em 5 partes:

1. **Criar novas tabelas** — `accounts`, `credit_card_details`
2. **Migrar credit_cards** — INSERT em `accounts` (type=CREDIT_CARD) + `credit_card_details` a partir de `credit_cards`
3. **Conta-padrão por tenant** — para cada tenant que possua transações sem `credit_card_id`, criar uma conta `CHECKING` chamada "Conta Padrão". Transações órfãs recebem esse `account_id`. Isso garante que o `NOT NULL` seja satisfeito sem perda de dados.
4. **Atualizar transações** — ADD COLUMN `account_id` (nullable) + `transfer_id`, UPDATE via `credit_card_id`, UPDATE órfãs via conta-padrão, ALTER NOT NULL, DROP `credit_card_id`
5. **Remover tabela legada** — DROP TABLE `credit_cards`

> **Por que conta-padrão e não deletar as transações órfãs?** Em sistemas financeiros, nunca se perde histórico. Uma transação sem conta de origem é ruim; uma transação deletada é pior. A conta-padrão preserva o dado e sinaliza que precisa de atenção manual do usuário.

---

## 9. Workflow de Desenvolvimento

- Cada agente trabalha em uma branch isolada (ex: `feature/accounts-domain`, `feature/accounts-service`, `feature/accounts-frontend`)
- Após validação individual, todas as branches são mescladas em `develop`
- `develop` → `main` exclusivamente via Merge Request (MR), nunca direto

### Divisão sugerida de agentes

| Agente | Responsabilidade | Branch sugerida |
|---|---|---|
| 1 | OpenAPI spec + Flyway migration (V5) | `feature/accounts-spec-migration` |
| 2 | Domínio backend: entidades, enums, repositórios | `feature/accounts-domain` |
| 3 | Serviços backend: AccountService + atualização TransactionService | `feature/accounts-service` |
| 4 | Controller backend (implementa interface gerada) | `feature/accounts-controller` |
| 5 | Frontend: account-list + account-form + atualização transaction-form | `feature/accounts-frontend` |

---

## 10. Testes (TDD)

Cada agente escreve os testes antes da implementação.

| Camada | Abordagem |
|---|---|
| Repositórios | Testcontainers + PostgreSQL real |
| Services | JUnit 5 + Mockito (unit); Testcontainers para fluxo de transferência |
| Controllers | `@WebMvcTest` com mock de service |
| Frontend | Vitest; testar lógica de exibição condicional dos campos de cartão |

### Casos críticos a cobrir

- Criação de conta com e sem `creditCardDetails`
- Tentativa de criar `creditCardDetails` em conta não-`CREDIT_CARD` → erro
- Cálculo de saldo com transações canceladas (não devem contar)
- Transferência: falha na segunda transação deve reverter a primeira (`@Transactional`)
- Isolamento de tenant: conta de tenant A não visível para tenant B
