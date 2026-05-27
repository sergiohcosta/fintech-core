# Account Management — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduzir a entidade `Account` como centro do sistema financeiro, migrar `CreditCard` para `Account(type=CREDIT_CARD)`, vincular transações a contas e exibir lista/formulário de contas no frontend.

**Architecture:** Composição — tabela `accounts` central + tabela satélite `credit_card_details` para campos específicos de cartão. Saldo calculado via `SUM(transactions)` sem coluna `balance`. Transferências como partidas dobradas: dois registros `INCOME`/`EXPENSE` com `transfer_id` compartilhado. Spec-first: openapi.yaml muda antes do código Java e Orval.

**Tech Stack:** Java 21, Spring Boot 4.0.1, JPA/Hibernate, Flyway, JUnit 5 + Mockito, OpenAPI Generator (spring, interfaceOnly=true), Angular 21 Zoneless, Orval, Angular Material 3, Vitest.

---

## Ordem de Execução (Multi-Agente)

```
Fase A (paralelo):   Track 1 (spec+migration)  +  Track 2 (domain)
Fase B:              Track 3 (services)  [aguarda Track 2 merged em develop]
                     Track 5 (frontend)  [aguarda Track 1 merged em develop]
Fase C:              Track 4 (controller) [aguarda Track 1 + Track 3 merged em develop]
```

Cada track trabalha na sua branch. Quando validado, merge em `develop`. Somente `develop` → `main` via MR.

---

## Mapa de Arquivos

### Track 1 — `feature/accounts-spec-migration`
| Ação | Arquivo |
|---|---|
| Modificar | `api-spec/openapi.yaml` |
| Criar | `backend/src/main/resources/db/migration/V5__accounts_migration.sql` |

### Track 2 — `feature/accounts-domain`
| Ação | Arquivo |
|---|---|
| Criar | `backend/src/main/java/com/fintech/api/domain/enums/AccountType.java` |
| Criar | `backend/src/main/java/com/fintech/api/domain/account/Account.java` |
| Criar | `backend/src/main/java/com/fintech/api/domain/account/CreditCardDetails.java` |
| Modificar | `backend/src/main/java/com/fintech/api/domain/transaction/Transaction.java` |
| Modificar | `backend/src/main/java/com/fintech/api/domain/enums/TransactionType.java` |
| Criar | `backend/src/main/java/com/fintech/api/repository/AccountRepository.java` |
| Criar | `backend/src/main/java/com/fintech/api/repository/CreditCardDetailsRepository.java` |

### Track 3 — `feature/accounts-service`
| Ação | Arquivo |
|---|---|
| Criar | `backend/src/main/java/com/fintech/api/dto/account/CreditCardDetailsDTO.java` |
| Criar | `backend/src/main/java/com/fintech/api/dto/account/CreditCardDetailsResponseDTO.java` |
| Criar | `backend/src/main/java/com/fintech/api/dto/account/AccountCreateDTO.java` |
| Criar | `backend/src/main/java/com/fintech/api/dto/account/AccountUpdateDTO.java` |
| Criar | `backend/src/main/java/com/fintech/api/dto/account/AccountResponseDTO.java` |
| Criar | `backend/src/main/java/com/fintech/api/service/AccountService.java` |
| Modificar | `backend/src/main/java/com/fintech/api/service/TransactionService.java` |
| Modificar | `backend/src/main/java/com/fintech/api/dto/transaction/TransactionRequestDTO.java` |
| Modificar | `backend/src/main/java/com/fintech/api/dto/transaction/TransactionResponseDTO.java` |
| Modificar | `backend/src/main/java/com/fintech/api/dto/transaction/TransactionUpdateDTO.java` |
| Criar | `backend/src/test/java/com/fintech/api/service/AccountServiceTest.java` |
| Modificar | `backend/src/test/java/com/fintech/api/service/TransactionServiceTest.java` |
| Deletar | `backend/src/main/java/com/fintech/api/domain/creditcard/CreditCard.java` |
| Deletar | `backend/src/main/java/com/fintech/api/repository/CreditCardRepository.java` |
| Deletar | `backend/src/main/java/com/fintech/api/dto/CreateCreditCardDTO.java` |
| Deletar | `backend/src/main/java/com/fintech/api/dto/CreditCardResponseDTO.java` |
| Deletar | `backend/src/main/java/com/fintech/api/dto/UpdateCreditCardDTO.java` |

### Track 4 — `feature/accounts-controller`
| Ação | Arquivo |
|---|---|
| Criar | `backend/src/main/java/com/fintech/api/controller/AccountController.java` |
| Modificar | `backend/src/main/java/com/fintech/api/controller/TransactionController.java` |
| Deletar | `backend/src/main/java/com/fintech/api/controller/CreditCardController.java` |
| Criar | `backend/src/test/java/com/fintech/api/controller/AccountControllerTest.java` |
| Modificar | `backend/src/test/java/com/fintech/api/controller/TransactionControllerTest.java` |

### Track 5 — `feature/accounts-frontend`
| Ação | Arquivo |
|---|---|
| Criar | `frontend/src/app/features/account/account-list/account-list.ts` |
| Criar | `frontend/src/app/features/account/account-list/account-list.html` |
| Criar | `frontend/src/app/features/account/account-list/account-list.scss` |
| Criar | `frontend/src/app/features/account/account-form/account-form.ts` |
| Criar | `frontend/src/app/features/account/account-form/account-form.html` |
| Criar | `frontend/src/app/features/account/account-form/account-form.scss` |
| Criar | `frontend/src/app/features/account/account-list/account-list.spec.ts` |
| Criar | `frontend/src/app/features/account/account-form/account-form.spec.ts` |
| Modificar | `frontend/src/app/features/transaction/transaction-form/transaction-form.ts` |
| Modificar | `frontend/src/app/app.routes.ts` |
| Deletar | `frontend/src/app/features/credit-card/` (diretório inteiro) |

---

## Track 1 — OpenAPI Spec + Flyway Migration

### Task 1: Atualizar openapi.yaml (spec-first)

**Branch:** `git checkout -b feature/accounts-spec-migration develop`

**Files:**
- Modify: `api-spec/openapi.yaml`

- [ ] **Step 1: Criar branch**

```bash
git checkout -b feature/accounts-spec-migration develop
```

- [ ] **Step 2: Atualizar enum `TransactionType` — remover TRANSFER**

Em `api-spec/openapi.yaml`, localizar e substituir:
```yaml
    TransactionType:
      type: string
      enum: [INCOME, EXPENSE, TRANSFER]
```
Por:
```yaml
    TransactionType:
      type: string
      enum: [INCOME, EXPENSE]
```

- [ ] **Step 3: Adicionar schemas de Account após o bloco `# --- Credit Cards ---`**

Substituir o bloco inteiro de Credit Cards schemas (de `# --- Credit Cards ---` até `UpdateCreditCardDTO` inclusive) por:

```yaml
    # --- Accounts ---

    AccountType:
      type: string
      enum: [CHECKING, INVESTMENT, CREDIT_CARD, CASH]

    CreditCardDetailsRequest:
      type: object
      properties:
        brand:
          $ref: '#/components/schemas/CardBrand'
        lastFourDigits:
          type: string
          minLength: 4
          maxLength: 4
          nullable: true
        limitAmount:
          type: number
          format: double
          nullable: true
        closingDay:
          type: integer
          minimum: 1
          maximum: 31
          nullable: true
        dueDay:
          type: integer
          minimum: 1
          maximum: 31
          nullable: true

    CreditCardDetailsResponse:
      type: object
      required: [closingDay, dueDay]
      properties:
        brand:
          $ref: '#/components/schemas/CardBrand'
          nullable: true
        lastFourDigits:
          type: string
          nullable: true
        limitAmount:
          type: number
          format: double
          nullable: true
        closingDay:
          type: integer
        dueDay:
          type: integer

    AccountCreateRequest:
      type: object
      required: [name, type]
      properties:
        name:
          type: string
          maxLength: 100
        type:
          $ref: '#/components/schemas/AccountType'
        color:
          type: string
          pattern: '^#([A-Fa-f0-9]{6})$'
          nullable: true
        icon:
          type: string
          nullable: true
        countInLiquidBalance:
          type: boolean
          nullable: true
        countInNetWorth:
          type: boolean
          nullable: true
        creditCardDetails:
          $ref: '#/components/schemas/CreditCardDetailsRequest'
          nullable: true

    AccountUpdateRequest:
      type: object
      properties:
        name:
          type: string
          maxLength: 100
          nullable: true
        color:
          type: string
          pattern: '^#([A-Fa-f0-9]{6})$'
          nullable: true
        icon:
          type: string
          nullable: true
        countInLiquidBalance:
          type: boolean
          nullable: true
        countInNetWorth:
          type: boolean
          nullable: true

    AccountResponse:
      type: object
      required: [id, name, type, countInLiquidBalance, countInNetWorth, active, balance]
      properties:
        id:
          type: string
          format: uuid
        name:
          type: string
        type:
          $ref: '#/components/schemas/AccountType'
        color:
          type: string
          nullable: true
        icon:
          type: string
          nullable: true
        countInLiquidBalance:
          type: boolean
        countInNetWorth:
          type: boolean
        active:
          type: boolean
        balance:
          type: number
          format: double
        creditCardDetails:
          $ref: '#/components/schemas/CreditCardDetailsResponse'
          nullable: true
```

- [ ] **Step 4: Atualizar `TransactionRequestDTO` — substituir `creditCardId` por `accountId`**

Localizar em `components/schemas/TransactionRequestDTO` e substituir:
```yaml
        creditCardId:
          type: string
          format: uuid
          nullable: true
```
Por:
```yaml
        accountId:
          type: string
          format: uuid
```
E adicionar `accountId` no array `required`: `required: [description, amount, date, type, accountId]`

- [ ] **Step 5: Atualizar `TransactionUpdateDTO` — substituir `creditCardId` por `accountId`**

Localizar em `components/schemas/TransactionUpdateDTO` e substituir:
```yaml
        creditCardId:
          type: string
          format: uuid
          nullable: true
```
Por:
```yaml
        accountId:
          type: string
          format: uuid
          nullable: true
```

- [ ] **Step 6: Atualizar `TransactionResponseDTO` — substituir `creditCardName` por `accountName` e adicionar `transferId`**

Localizar em `components/schemas/TransactionResponseDTO` e substituir:
```yaml
        creditCardName:
          type: string
          nullable: true
```
Por:
```yaml
        accountName:
          type: string
          nullable: true
        transferId:
          type: string
          format: uuid
          nullable: true
```
Adicionar `accountName` no array `required`: `required: [id, description, amount, date, type, status, accountName]`

- [ ] **Step 7: Substituir paths de credit-cards por accounts**

Localizar e remover o bloco:
```yaml
  # --- Credit Cards ---

  /api/credit-cards:
    ...
  /api/credit-cards/{id}:
    ...
```

Adicionar no lugar:
```yaml
  # --- Accounts ---

  /api/accounts:
    get:
      tags: [accounts]
      operationId: listAccounts
      responses:
        '200':
          description: Lista de contas ativas do tenant (com saldo calculado)
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/AccountResponse'
    post:
      tags: [accounts]
      operationId: createAccount
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/AccountCreateRequest'
      responses:
        '201':
          description: Conta criada
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AccountResponse'

  /api/accounts/{id}:
    get:
      tags: [accounts]
      operationId: getAccount
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '200':
          description: Conta encontrada com saldo
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AccountResponse'
        '404':
          description: Conta não encontrada
    put:
      tags: [accounts]
      operationId: updateAccount
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/AccountUpdateRequest'
      responses:
        '200':
          description: Conta atualizada
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AccountResponse'
        '404':
          description: Conta não encontrada
    delete:
      tags: [accounts]
      operationId: deleteAccount
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '204':
          description: Conta arquivada (soft-delete)
        '404':
          description: Conta não encontrada
```

- [ ] **Step 8: Verificar compilação do YAML**

```bash
cd /home/sergio/fintech-core
# Verificar que não há erros de sintaxe YAML básicos
python3 -c "import yaml; yaml.safe_load(open('api-spec/openapi.yaml'))" && echo "YAML OK"
```
Resultado esperado: `YAML OK`

- [ ] **Step 9: Commit**

```bash
git add api-spec/openapi.yaml
git commit -m "feat(openapi): substitui credit-cards por accounts — spec-first para account management"
```

---

### Task 2: Migration Flyway V5

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__accounts_migration.sql`

- [ ] **Step 1: Criar o arquivo de migration**

Criar `backend/src/main/resources/db/migration/V5__accounts_migration.sql` com o conteúdo:

```sql
-- V5__accounts_migration.sql
-- Migra credit_cards para accounts + credit_card_details
-- Atualiza transactions para referenciar accounts diretamente

-- 1. Criar tabela accounts
CREATE TABLE accounts (
    id                      UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    name                    VARCHAR(100) NOT NULL,
    type                    VARCHAR(20)  NOT NULL,
    color                   VARCHAR(7),
    icon                    VARCHAR(50),
    count_in_liquid_balance BOOLEAN NOT NULL DEFAULT true,
    count_in_net_worth      BOOLEAN NOT NULL DEFAULT true,
    active                  BOOLEAN NOT NULL DEFAULT true,
    tenant_id               UUID NOT NULL REFERENCES tenants(id),
    created_by              UUID REFERENCES users(id),
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_accounts_tenant       ON accounts(tenant_id);
CREATE INDEX idx_accounts_tenant_type  ON accounts(tenant_id, type);

-- 2. Criar tabela credit_card_details
CREATE TABLE credit_card_details (
    id               UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    account_id       UUID NOT NULL UNIQUE REFERENCES accounts(id) ON DELETE CASCADE,
    brand            VARCHAR(20),
    last_four_digits VARCHAR(4),
    limit_amount     DECIMAL(19, 2),
    closing_day      INTEGER,
    due_day          INTEGER
);

-- 3. Migrar credit_cards → accounts
-- count_in_liquid_balance=false: cartão não entra no saldo do dia (a grana sai na fatura)
INSERT INTO accounts (
    id, name, type, color,
    count_in_liquid_balance, count_in_net_worth, active,
    tenant_id, created_by, created_at, updated_at
)
SELECT
    id, name, 'CREDIT_CARD', color,
    false, true, true,
    tenant_id, user_id, created_at, updated_at
FROM credit_cards;

-- 4. Migrar credit_cards → credit_card_details
INSERT INTO credit_card_details (account_id, brand, last_four_digits, limit_amount, closing_day, due_day)
SELECT id, brand, last_four_digits, limit_amount, closing_day, due_day
FROM credit_cards;

-- 5. Criar conta-padrão por tenant com transações sem credit_card_id
-- Preserva histórico: nunca deleta transações, apenas as ancora em uma conta CHECKING
INSERT INTO accounts (name, type, count_in_liquid_balance, count_in_net_worth, active, tenant_id, created_at, updated_at)
SELECT DISTINCT
    'Conta Padrão',
    'CHECKING',
    true,
    true,
    true,
    t.tenant_id,
    NOW(),
    NOW()
FROM transactions t
WHERE t.credit_card_id IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM accounts a
      WHERE a.tenant_id = t.tenant_id
        AND a.name = 'Conta Padrão'
  );

-- 6. Adicionar colunas em transactions
ALTER TABLE transactions ADD COLUMN account_id UUID REFERENCES accounts(id);
ALTER TABLE transactions ADD COLUMN transfer_id UUID;

-- 7. Vincular transações com credit_card_id ao account correspondente
UPDATE transactions
SET account_id = credit_card_id
WHERE credit_card_id IS NOT NULL;

-- 8. Vincular transações órfãs à conta-padrão do tenant
UPDATE transactions t
SET account_id = (
    SELECT a.id FROM accounts a
    WHERE a.tenant_id = t.tenant_id
      AND a.name = 'Conta Padrão'
    LIMIT 1
)
WHERE t.credit_card_id IS NULL;

-- 9. Tornar account_id obrigatório e remover credit_card_id
ALTER TABLE transactions ALTER COLUMN account_id SET NOT NULL;
ALTER TABLE transactions DROP COLUMN credit_card_id;

-- 10. Remover tabela legada
DROP TABLE credit_cards;
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/resources/db/migration/V5__accounts_migration.sql
git commit -m "feat(migration): V5 — migra credit_cards para accounts e atualiza transactions"
```

---

## Track 2 — Backend Domain

### Task 3: Enum + Entidades de Domínio

**Branch:** `git checkout -b feature/accounts-domain develop`

**Files:**
- Create: `backend/src/main/java/com/fintech/api/domain/enums/AccountType.java`
- Create: `backend/src/main/java/com/fintech/api/domain/account/Account.java`
- Create: `backend/src/main/java/com/fintech/api/domain/account/CreditCardDetails.java`

- [ ] **Step 1: Criar branch**

```bash
git checkout -b feature/accounts-domain develop
```

- [ ] **Step 2: Criar `AccountType.java`**

```java
package com.fintech.api.domain.enums;

public enum AccountType {
    CHECKING,     // Conta Corrente
    INVESTMENT,   // Investimento
    CREDIT_CARD,  // Cartão de Crédito
    CASH          // Carteira Física
}
```

- [ ] **Step 3: Criar `Account.java`**

```java
package com.fintech.api.domain.account;

import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType type;

    @Column(length = 7)
    private String color;

    @Column(length = 50)
    private String icon;

    @Column(nullable = false)
    private boolean countInLiquidBalance;

    @Column(nullable = false)
    private boolean countInNetWorth;

    // Campo nomeado 'active' (não 'isActive') para evitar o bug do Lombok:
    // boolean isX gera getter isIsX(). 'active' gera isActive() corretamente.
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    @ToString.Exclude
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    @ToString.Exclude
    private User createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 4: Criar `CreditCardDetails.java`**

```java
package com.fintech.api.domain.account;

import com.fintech.api.domain.enums.CardBrand;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "credit_card_details")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CreditCardDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false, unique = true)
    @ToString.Exclude
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CardBrand brand;

    @Column(length = 4)
    private String lastFourDigits;

    @Column(precision = 19, scale = 2)
    private BigDecimal limitAmount;

    private Integer closingDay;
    private Integer dueDay;
}
```

- [ ] **Step 5: Verificar compilação**

```bash
cd /home/sergio/fintech-core/backend
./mvnw compile -q 2>&1 | tail -20
```
Resultado esperado: nenhum erro de compilação.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/fintech/api/domain/enums/AccountType.java \
        src/main/java/com/fintech/api/domain/account/Account.java \
        src/main/java/com/fintech/api/domain/account/CreditCardDetails.java
git commit -m "feat(domain): adiciona AccountType, Account e CreditCardDetails"
```

---

### Task 4: Atualizar Transaction + TransactionType

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/domain/transaction/Transaction.java`
- Modify: `backend/src/main/java/com/fintech/api/domain/enums/TransactionType.java`

- [ ] **Step 1: Remover TRANSFER do enum `TransactionType`**

Substituir o conteúdo de `TransactionType.java` por:
```java
package com.fintech.api.domain.enums;

public enum TransactionType {
    INCOME,   // Receita (Salário, Dividendo, Transferência recebida)
    EXPENSE   // Despesa (Conta, Mercado, Transferência enviada)
}
```

- [ ] **Step 2: Atualizar `Transaction.java`**

Substituir os campos `creditCard` e adicionar `account` + `transferId`. Localizar:
```java
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credit_card_id")
    @ToString.Exclude
    private CreditCard creditCard; 
```
Substituir por:
```java
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    @ToString.Exclude
    private Account account;

    // Liga as duas pernas de uma transferência (nullable para transações comuns)
    private UUID transferId;
```

Remover o import de `CreditCard` e adicionar:
```java
import com.fintech.api.domain.account.Account;
```

- [ ] **Step 3: Verificar compilação**

```bash
cd /home/sergio/fintech-core/backend
./mvnw compile -q 2>&1 | tail -20
```
Erros esperados: classes que ainda referenciam `CreditCard` ou `TRANSFER` (serão corrigidas no Track 3). Erros de compilação em `TransactionService`, `TransactionResponseDTO` etc. são normais aqui.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/fintech/api/domain/transaction/Transaction.java \
        src/main/java/com/fintech/api/domain/enums/TransactionType.java
git commit -m "feat(domain): atualiza Transaction — substitui creditCard por account + transferId"
```

---

### Task 5: Repositories

**Files:**
- Create: `backend/src/main/java/com/fintech/api/repository/AccountRepository.java`
- Create: `backend/src/main/java/com/fintech/api/repository/CreditCardDetailsRepository.java`

- [ ] **Step 1: Criar `AccountRepository.java`**

```java
package com.fintech.api.repository;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findAllByTenantAndActiveTrueOrderByName(Tenant tenant);

    Optional<Account> findByIdAndTenant(UUID id, Tenant tenant);

    @Query("""
        SELECT COALESCE(SUM(
            CASE WHEN t.type = :incomeType THEN t.amount ELSE -t.amount END
        ), 0)
        FROM Transaction t
        WHERE t.account = :account
          AND t.status <> :cancelledStatus
    """)
    BigDecimal calculateBalance(
        @Param("account") Account account,
        @Param("incomeType") TransactionType incomeType,
        @Param("cancelledStatus") TransactionStatus cancelledStatus
    );
}
```

- [ ] **Step 2: Criar `CreditCardDetailsRepository.java`**

```java
package com.fintech.api.repository;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.account.CreditCardDetails;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CreditCardDetailsRepository extends JpaRepository<CreditCardDetails, UUID> {

    Optional<CreditCardDetails> findByAccount(Account account);
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/fintech/api/repository/AccountRepository.java \
        src/main/java/com/fintech/api/repository/CreditCardDetailsRepository.java
git commit -m "feat(repository): adiciona AccountRepository e CreditCardDetailsRepository"
```

---

## Track 3 — Backend Services

> **Pré-requisito:** Track 2 (`feature/accounts-domain`) deve estar merged em `develop`.
> Criar branch a partir do `develop` atualizado.

### Task 6: DTOs de Account

**Branch:** `git checkout -b feature/accounts-service develop`

**Files:**
- Create: `backend/src/main/java/com/fintech/api/dto/account/CreditCardDetailsDTO.java`
- Create: `backend/src/main/java/com/fintech/api/dto/account/CreditCardDetailsResponseDTO.java`
- Create: `backend/src/main/java/com/fintech/api/dto/account/AccountCreateDTO.java`
- Create: `backend/src/main/java/com/fintech/api/dto/account/AccountUpdateDTO.java`
- Create: `backend/src/main/java/com/fintech/api/dto/account/AccountResponseDTO.java`

- [ ] **Step 1: Criar branch**

```bash
git checkout -b feature/accounts-service develop
```

- [ ] **Step 2: Criar `CreditCardDetailsDTO.java`** (entrada — campos opcionais)

```java
package com.fintech.api.dto.account;

import com.fintech.api.domain.enums.CardBrand;
import java.math.BigDecimal;

public record CreditCardDetailsDTO(
        CardBrand brand,
        String lastFourDigits,
        BigDecimal limitAmount,
        Integer closingDay,
        Integer dueDay
) {}
```

- [ ] **Step 3: Criar `CreditCardDetailsResponseDTO.java`** (saída)

```java
package com.fintech.api.dto.account;

import com.fintech.api.domain.enums.CardBrand;
import java.math.BigDecimal;

public record CreditCardDetailsResponseDTO(
        CardBrand brand,
        String lastFourDigits,
        BigDecimal limitAmount,
        Integer closingDay,
        Integer dueDay
) {}
```

- [ ] **Step 4: Criar `AccountCreateDTO.java`**

```java
package com.fintech.api.dto.account;

import com.fintech.api.domain.enums.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AccountCreateDTO(
        @NotBlank(message = "O nome é obrigatório")
        @Size(max = 100)
        String name,

        @NotNull(message = "O tipo é obrigatório")
        AccountType type,

        String color,
        String icon,
        Boolean countInLiquidBalance,
        Boolean countInNetWorth,
        CreditCardDetailsDTO creditCardDetails
) {}
```

- [ ] **Step 5: Criar `AccountUpdateDTO.java`**

```java
package com.fintech.api.dto.account;

public record AccountUpdateDTO(
        String name,
        String color,
        String icon,
        Boolean countInLiquidBalance,
        Boolean countInNetWorth
) {}
```

- [ ] **Step 6: Criar `AccountResponseDTO.java`**

```java
package com.fintech.api.dto.account;

import com.fintech.api.domain.enums.AccountType;
import java.math.BigDecimal;
import java.util.UUID;

public record AccountResponseDTO(
        UUID id,
        String name,
        AccountType type,
        String color,
        String icon,
        boolean countInLiquidBalance,
        boolean countInNetWorth,
        boolean active,
        BigDecimal balance,
        CreditCardDetailsResponseDTO creditCardDetails
) {}
```

- [ ] **Step 7: Atualizar `TransactionRequestDTO.java`** — substituir `creditCardId` por `accountId`

```java
package com.fintech.api.dto.transaction;

import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionRequestDTO(
        @NotBlank(message = "A descrição é obrigatória") String description,
        @NotNull(message = "O valor é obrigatório") @DecimalMin(value = "0.01") BigDecimal amount,
        @NotNull(message = "A data é obrigatória") LocalDate date,
        @NotNull(message = "O tipo é obrigatório") TransactionType type,
        TransactionStatus status,
        Integer totalInstallments,
        UUID categoryId,
        @NotNull(message = "A conta é obrigatória") UUID accountId
) {}
```

- [ ] **Step 8: Atualizar `TransactionUpdateDTO.java`** — substituir `creditCardId` por `accountId`

Localizar em `TransactionUpdateDTO.java` o campo `UUID creditCardId` e substituir por:
```java
        UUID accountId
```

- [ ] **Step 9: Atualizar `TransactionResponseDTO.java`** — substituir `creditCardName` por `accountName` + adicionar `transferId`

```java
package com.fintech.api.dto.transaction;

import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.transaction.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionResponseDTO(
        UUID id,
        String description,
        BigDecimal amount,
        LocalDate date,
        TransactionType type,
        TransactionStatus status,
        String installmentLabel,
        String categoryName,
        String accountName,
        UUID transferId
) {
    public static TransactionResponseDTO fromEntity(Transaction t) {
        String installLabel = null;
        if (t.getTotalInstallments() != null && t.getTotalInstallments() > 1) {
            installLabel = t.getInstallmentNumber() + "/" + t.getTotalInstallments();
        }
        return new TransactionResponseDTO(
                t.getId(),
                t.getDescription(),
                t.getAmount(),
                t.getDate(),
                t.getType(),
                t.getStatus(),
                installLabel,
                t.getCategory() != null ? t.getCategory().getName() : null,
                t.getAccount() != null ? t.getAccount().getName() : null,
                t.getTransferId()
        );
    }
}
```

- [ ] **Step 10: Commit dos DTOs**

```bash
git add src/main/java/com/fintech/api/dto/
git commit -m "feat(dto): adiciona DTOs de account e atualiza DTOs de transaction"
```

---

### Task 7: AccountService (TDD)

**Files:**
- Create: `backend/src/main/java/com/fintech/api/service/AccountService.java`
- Create: `backend/src/test/java/com/fintech/api/service/AccountServiceTest.java`

- [ ] **Step 1: Escrever o teste antes da implementação**

Criar `backend/src/test/java/com/fintech/api/service/AccountServiceTest.java`:

```java
package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.account.CreditCardDetails;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.enums.CardBrand;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.account.*;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CreditCardDetailsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock CreditCardDetailsRepository creditCardDetailsRepository;
    @InjectMocks AccountService service;

    @Test
    @DisplayName("Conta corrente criada com countInLiquidBalance=true por padrão")
    void checkingDefaultsToLiquid() {
        User user = buildUser();
        AccountCreateDTO dto = new AccountCreateDTO(
                "Bradesco", AccountType.CHECKING, "#FF0000", null, null, null, null);
        Account saved = Account.builder().id(UUID.randomUUID()).name("Bradesco")
                .type(AccountType.CHECKING).countInLiquidBalance(true).countInNetWorth(true)
                .active(true).tenant(user.getTenant()).build();

        when(accountRepository.save(any())).thenReturn(saved);
        when(accountRepository.calculateBalance(any(), any(), any())).thenReturn(BigDecimal.ZERO);

        AccountResponseDTO result = service.create(dto, user);

        assertThat(result.countInLiquidBalance()).isTrue();
        assertThat(result.balance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Conta de investimento criada com countInLiquidBalance=false por padrão")
    void investmentDefaultsToNotLiquid() {
        User user = buildUser();
        AccountCreateDTO dto = new AccountCreateDTO(
                "Tesouro Direto", AccountType.INVESTMENT, null, null, null, null, null);
        Account saved = Account.builder().id(UUID.randomUUID()).name("Tesouro Direto")
                .type(AccountType.INVESTMENT).countInLiquidBalance(false).countInNetWorth(true)
                .active(true).tenant(user.getTenant()).build();

        when(accountRepository.save(any())).thenReturn(saved);
        when(accountRepository.calculateBalance(any(), any(), any())).thenReturn(BigDecimal.ZERO);

        AccountResponseDTO result = service.create(dto, user);

        assertThat(result.countInLiquidBalance()).isFalse();
    }

    @Test
    @DisplayName("Cartão de crédito persiste credit_card_details")
    void creditCardPersistsDetails() {
        User user = buildUser();
        CreditCardDetailsDTO details = new CreditCardDetailsDTO(
                CardBrand.VISA, "1234", new BigDecimal("5000"), 10, 15);
        AccountCreateDTO dto = new AccountCreateDTO(
                "Nubank", AccountType.CREDIT_CARD, "#8A05BE", null, null, null, details);
        Account saved = Account.builder().id(UUID.randomUUID()).name("Nubank")
                .type(AccountType.CREDIT_CARD).countInLiquidBalance(false).countInNetWorth(true)
                .active(true).tenant(user.getTenant()).build();

        when(accountRepository.save(any())).thenReturn(saved);
        when(accountRepository.calculateBalance(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(creditCardDetailsRepository.findByAccount(any())).thenReturn(Optional.empty());

        service.create(dto, user);

        ArgumentCaptor<CreditCardDetails> captor = ArgumentCaptor.forClass(CreditCardDetails.class);
        verify(creditCardDetailsRepository).save(captor.capture());
        assertThat(captor.getValue().getBrand()).isEqualTo(CardBrand.VISA);
        assertThat(captor.getValue().getClosingDay()).isEqualTo(10);
    }

    @Test
    @DisplayName("findById lança EntityNotFoundException para conta de outro tenant")
    void findByIdThrowsForOtherTenant() {
        User user = buildUser();
        when(accountRepository.findByIdAndTenant(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(UUID.randomUUID(), user))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Conta não encontrada");
    }

    @Test
    @DisplayName("archive define active=false")
    void archiveSetsActiveFalse() {
        User user = buildUser();
        Account account = Account.builder().id(UUID.randomUUID()).name("Corrente")
                .type(AccountType.CHECKING).active(true).tenant(user.getTenant()).build();
        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));

        service.archive(account.getId(), user);

        assertThat(account.isActive()).isFalse();
    }

    private User buildUser() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        User user = new User();
        user.setTenant(tenant);
        return user;
    }
}
```

- [ ] **Step 2: Rodar os testes para confirmar que falham**

```bash
cd /home/sergio/fintech-core/backend
./mvnw test -pl . -Dtest=AccountServiceTest -q 2>&1 | tail -10
```
Resultado esperado: COMPILATION ERROR — `AccountService` não existe ainda.

- [ ] **Step 3: Implementar `AccountService.java`**

```java
package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.account.CreditCardDetails;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.account.*;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CreditCardDetailsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final CreditCardDetailsRepository creditCardDetailsRepository;

    @Transactional(readOnly = true)
    public List<AccountResponseDTO> findAll(User user) {
        return accountRepository.findAllByTenantAndActiveTrueOrderByName(user.getTenant())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponseDTO findById(UUID id, User user) {
        Account account = findAccount(id, user);
        return toResponse(account);
    }

    @Transactional
    public AccountResponseDTO create(AccountCreateDTO dto, User user) {
        boolean liquidDefault = dto.type() == AccountType.CHECKING || dto.type() == AccountType.CASH;

        Account account = Account.builder()
                .name(dto.name())
                .type(dto.type())
                .color(dto.color())
                .icon(dto.icon())
                .countInLiquidBalance(dto.countInLiquidBalance() != null ? dto.countInLiquidBalance() : liquidDefault)
                .countInNetWorth(dto.countInNetWorth() != null ? dto.countInNetWorth() : true)
                .tenant(user.getTenant())
                .createdBy(user)
                .build();

        account = accountRepository.save(account);

        if (dto.type() == AccountType.CREDIT_CARD && dto.creditCardDetails() != null) {
            saveCreditCardDetails(account, dto.creditCardDetails());
        }

        return toResponse(account);
    }

    @Transactional
    public AccountResponseDTO update(UUID id, AccountUpdateDTO dto, User user) {
        Account account = findAccount(id, user);

        if (dto.name() != null)                   account.setName(dto.name());
        if (dto.color() != null)                  account.setColor(dto.color());
        if (dto.icon() != null)                   account.setIcon(dto.icon());
        if (dto.countInLiquidBalance() != null)   account.setCountInLiquidBalance(dto.countInLiquidBalance());
        if (dto.countInNetWorth() != null)        account.setCountInNetWorth(dto.countInNetWorth());

        return toResponse(account);
    }

    @Transactional
    public void archive(UUID id, User user) {
        Account account = findAccount(id, user);
        account.setActive(false);
    }

    private Account findAccount(UUID id, User user) {
        return accountRepository.findByIdAndTenant(id, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Conta não encontrada."));
    }

    private void saveCreditCardDetails(Account account, CreditCardDetailsDTO dto) {
        CreditCardDetails details = CreditCardDetails.builder()
                .account(account)
                .brand(dto.brand())
                .lastFourDigits(dto.lastFourDigits())
                .limitAmount(dto.limitAmount())
                .closingDay(dto.closingDay())
                .dueDay(dto.dueDay())
                .build();
        creditCardDetailsRepository.save(details);
    }

    private AccountResponseDTO toResponse(Account account) {
        BigDecimal balance = accountRepository.calculateBalance(
                account, TransactionType.INCOME, TransactionStatus.CANCELLED);

        CreditCardDetailsResponseDTO detailsDto = null;
        if (account.getType() == AccountType.CREDIT_CARD) {
            detailsDto = creditCardDetailsRepository.findByAccount(account)
                    .map(d -> new CreditCardDetailsResponseDTO(
                            d.getBrand(), d.getLastFourDigits(), d.getLimitAmount(),
                            d.getClosingDay(), d.getDueDay()))
                    .orElse(null);
        }

        return new AccountResponseDTO(
                account.getId(), account.getName(), account.getType(),
                account.getColor(), account.getIcon(),
                account.isCountInLiquidBalance(), account.isCountInNetWorth(),
                account.isActive(), balance, detailsDto);
    }
}
```

- [ ] **Step 4: Rodar os testes para confirmar que passam**

```bash
./mvnw test -pl . -Dtest=AccountServiceTest -q 2>&1 | tail -10
```
Resultado esperado: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/fintech/api/service/AccountService.java \
        src/test/java/com/fintech/api/service/AccountServiceTest.java
git commit -m "feat(service): implementa AccountService com TDD"
```

---

### Task 8: Atualizar TransactionService

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/TransactionService.java`
- Modify: `backend/src/test/java/com/fintech/api/service/TransactionServiceTest.java`

- [ ] **Step 1: Atualizar `TransactionServiceTest.java`** — remover CreditCardRepository, adicionar AccountRepository

Substituir o conteúdo completo por:

```java
package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.transaction.TransactionRequestDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock TransactionRepository repository;
    @Mock CategoryRepository categoryRepository;
    @Mock AccountRepository accountRepository;
    @InjectMocks TransactionService service;

    @Test
    @DisplayName("Cria transação única quando installments=1")
    void createsSingleTransaction() {
        User user = buildUser();
        Account account = buildAccount(user);
        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Salário", new BigDecimal("5000.00"), LocalDate.now(),
                TransactionType.INCOME, null, 1, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        List<TransactionResponseDTO> result = service.create(dto, user);

        assertThat(result).hasSize(1);
        verify(repository, times(1)).save(any());
    }

    @Test
    @DisplayName("Cria N parcelas quando totalInstallments=N")
    void createsInstallments() {
        User user = buildUser();
        Account account = buildAccount(user);
        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Notebook", new BigDecimal("3000.00"), LocalDate.now(),
                TransactionType.EXPENSE, null, 3, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        List<TransactionResponseDTO> result = service.create(dto, user);

        assertThat(result).hasSize(3);
        verify(repository, times(3)).save(any());
    }

    @Test
    @DisplayName("createTransfer cria duas transações espelhadas com mesmo transferId")
    void createTransferMirrorsTransactions() {
        User user = buildUser();
        Account from = buildAccount(user);
        Account to = buildAccount(user);
        UUID fromId = from.getId();
        UUID toId = to.getId();

        when(accountRepository.findByIdAndTenant(fromId, user.getTenant())).thenReturn(Optional.of(from));
        when(accountRepository.findByIdAndTenant(toId, user.getTenant())).thenReturn(Optional.of(to));
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        service.createTransfer(fromId, toId, new BigDecimal("500.00"), LocalDate.now(), user);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(repository, times(2)).save(captor.capture());

        List<Transaction> saved = captor.getAllValues();
        Transaction expense = saved.stream().filter(t -> t.getType() == TransactionType.EXPENSE).findFirst().orElseThrow();
        Transaction income = saved.stream().filter(t -> t.getType() == TransactionType.INCOME).findFirst().orElseThrow();

        assertThat(expense.getAccount()).isEqualTo(from);
        assertThat(income.getAccount()).isEqualTo(to);
        assertThat(expense.getTransferId()).isNotNull();
        assertThat(expense.getTransferId()).isEqualTo(income.getTransferId());
        assertThat(expense.getAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("findById lança EntityNotFoundException para transação de outro tenant")
    void findByIdThrowsForOtherTenant() {
        User user = buildUser();
        when(repository.findByIdAndTenant(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(UUID.randomUUID(), user))
                .isInstanceOf(EntityNotFoundException.class);
    }

    private User buildUser() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        User user = new User();
        user.setTenant(tenant);
        return user;
    }

    private Account buildAccount(User user) {
        return Account.builder()
                .id(UUID.randomUUID())
                .type(AccountType.CHECKING)
                .tenant(user.getTenant())
                .build();
    }
}
```

- [ ] **Step 2: Rodar testes para confirmar que falham**

```bash
./mvnw test -pl . -Dtest=TransactionServiceTest -q 2>&1 | tail -10
```
Resultado esperado: erros de compilação — `TransactionService` ainda usa `CreditCardRepository`.

- [ ] **Step 3: Atualizar `TransactionService.java`**

Substituir o conteúdo completo por:

```java
package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.transaction.TransactionRequestDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.dto.transaction.TransactionUpdateDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository repository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public List<TransactionResponseDTO> findAll(User user) {
        return repository.findAllByTenantOrderByDateDesc(user.getTenant())
                .stream()
                .map(TransactionResponseDTO::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public TransactionResponseDTO findById(UUID id, User user) {
        return TransactionResponseDTO.fromEntity(
                repository.findByIdAndTenant(id, user.getTenant())
                        .orElseThrow(() -> new EntityNotFoundException("Transação não encontrada.")));
    }

    @Transactional
    public List<TransactionResponseDTO> create(TransactionRequestDTO dto, User user) {
        Category category = resolveCategory(dto.categoryId(), user);
        Account account = resolveAccount(dto.accountId(), user);

        int installments = (dto.totalInstallments() != null && dto.totalInstallments() > 0)
                ? dto.totalInstallments() : 1;
        BigDecimal installmentAmount = dto.amount().divide(BigDecimal.valueOf(installments), 2, RoundingMode.HALF_EVEN);

        List<Transaction> created = new ArrayList<>();
        for (int i = 0; i < installments; i++) {
            created.add(repository.save(Transaction.builder()
                    .description(dto.description())
                    .amount(installmentAmount)
                    .date(dto.date().plusMonths(i))
                    .type(dto.type())
                    .status(dto.status() != null ? dto.status() : TransactionStatus.PENDING)
                    .installmentNumber(i + 1)
                    .totalInstallments(installments)
                    .tenant(user.getTenant())
                    .user(user)
                    .category(category)
                    .account(account)
                    .build()));
        }
        return created.stream().map(TransactionResponseDTO::fromEntity).toList();
    }

    @Transactional
    public void createTransfer(UUID fromAccountId, UUID toAccountId, BigDecimal amount, LocalDate date, User user) {
        Account from = resolveAccount(fromAccountId, user);
        Account to = resolveAccount(toAccountId, user);
        UUID transferId = UUID.randomUUID();

        repository.save(Transaction.builder()
                .description("Transferência")
                .amount(amount).date(date)
                .type(TransactionType.EXPENSE)
                .status(TransactionStatus.PAID)
                .installmentNumber(1).totalInstallments(1)
                .tenant(user.getTenant()).user(user)
                .account(from).transferId(transferId)
                .build());

        repository.save(Transaction.builder()
                .description("Transferência")
                .amount(amount).date(date)
                .type(TransactionType.INCOME)
                .status(TransactionStatus.PAID)
                .installmentNumber(1).totalInstallments(1)
                .tenant(user.getTenant()).user(user)
                .account(to).transferId(transferId)
                .build());
    }

    @Transactional
    public TransactionResponseDTO update(UUID id, TransactionUpdateDTO dto, User user) {
        Transaction t = repository.findByIdAndTenant(id, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Transação não encontrada."));

        if (dto.description() != null) t.setDescription(dto.description());
        if (dto.amount() != null)      t.setAmount(dto.amount());
        if (dto.date() != null)        t.setDate(dto.date());
        if (dto.type() != null)        t.setType(dto.type());
        if (dto.status() != null)      t.setStatus(dto.status());

        if (dto.categoryId() != null) {
            t.setCategory(resolveCategory(dto.categoryId(), user));
        }
        if (dto.accountId() != null) {
            t.setAccount(resolveAccount(dto.accountId(), user));
        }
        return TransactionResponseDTO.fromEntity(t);
    }

    @Transactional
    public void delete(UUID id, User user) {
        repository.delete(
                repository.findByIdAndTenant(id, user.getTenant())
                        .orElseThrow(() -> new EntityNotFoundException("Transação não encontrada.")));
    }

    private Category resolveCategory(UUID categoryId, User user) {
        if (categoryId == null) return null;
        return categoryRepository.findByIdAndTenantId(categoryId, user.getTenant().getId())
                .orElseThrow(() -> new EntityNotFoundException("Categoria não encontrada."));
    }

    private Account resolveAccount(UUID accountId, User user) {
        return accountRepository.findByIdAndTenant(accountId, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Conta não encontrada."));
    }
}
```

- [ ] **Step 4: Rodar testes**

```bash
./mvnw test -pl . -Dtest=TransactionServiceTest -q 2>&1 | tail -10
```
Resultado esperado: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Deletar arquivos de CreditCard que não são mais necessários**

```bash
rm src/main/java/com/fintech/api/domain/creditcard/CreditCard.java
rm src/main/java/com/fintech/api/repository/CreditCardRepository.java
rm src/main/java/com/fintech/api/dto/CreateCreditCardDTO.java
rm src/main/java/com/fintech/api/dto/CreditCardResponseDTO.java
rm src/main/java/com/fintech/api/dto/UpdateCreditCardDTO.java
```

- [ ] **Step 6: Verificar compilação completa**

```bash
./mvnw compile -q 2>&1 | tail -20
```
Erros esperados agora: apenas em `CreditCardController` (será tratado no Track 4).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(service): atualiza TransactionService para accounts + adiciona createTransfer"
```

---

## Track 4 — Backend Controller

> **Pré-requisito:** `feature/accounts-spec-migration` e `feature/accounts-service` devem estar merged em `develop`.
> Criar branch a partir do `develop` atualizado.

### Task 9: AccountController (TDD)

**Branch:** `git checkout -b feature/accounts-controller develop`

**Files:**
- Create: `backend/src/main/java/com/fintech/api/controller/AccountController.java`
- Create: `backend/src/test/java/com/fintech/api/controller/AccountControllerTest.java`

- [ ] **Step 1: Criar branch**

```bash
git checkout -b feature/accounts-controller develop
```

- [ ] **Step 2: Gerar a interface OpenAPI no backend**

O plugin `openapi-generator-maven-plugin` gera `AccountsApi` durante a fase `generate-sources`:
```bash
cd /home/sergio/fintech-core/backend
./mvnw generate-sources -q 2>&1 | tail -10
```
A interface gerada estará em `target/generated-sources/openapi/src/main/java/com/fintech/api/openapi/AccountsApi.java`.

- [ ] **Step 3: Escrever `AccountControllerTest.java`**

```java
package com.fintech.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.api.config.SecurityConfigurations;
import com.fintech.api.config.SecurityFilter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.account.AccountCreateDTO;
import com.fintech.api.dto.account.AccountResponseDTO;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Import({ SecurityConfigurations.class, SecurityFilter.class })
class AccountControllerTest {

    private MockMvc mockMvc;

    @Autowired WebApplicationContext context;
    @MockitoBean AccountService accountService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean TokenService tokenService;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private User user;
    private String token;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        user = new User();
        user.setEmail("test@test.com");
        user.setTenant(tenant);
        token = "Bearer mock-token";

        when(tokenService.validateToken(anyString())).thenReturn(user.getEmail());
        when(userRepository.findByEmail(user.getEmail())).thenReturn(user);
    }

    @Test
    @DisplayName("GET /api/accounts retorna 200 com lista de contas")
    void listAccountsReturns200() throws Exception {
        AccountResponseDTO dto = new AccountResponseDTO(
                UUID.randomUUID(), "Bradesco", AccountType.CHECKING,
                "#FF0000", null, true, true, true, BigDecimal.ZERO, null);

        when(accountService.findAll(any())).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/accounts").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Bradesco"))
                .andExpect(jsonPath("$[0].type").value("CHECKING"))
                .andExpect(jsonPath("$[0].balance").value(0));
    }

    @Test
    @DisplayName("POST /api/accounts retorna 201 com conta criada")
    void createAccountReturns201() throws Exception {
        AccountCreateDTO req = new AccountCreateDTO(
                "Nubank", AccountType.CREDIT_CARD, "#8A05BE", null, null, null, null);
        AccountResponseDTO res = new AccountResponseDTO(
                UUID.randomUUID(), "Nubank", AccountType.CREDIT_CARD,
                "#8A05BE", null, false, true, true, BigDecimal.ZERO, null);

        when(accountService.create(any(), any())).thenReturn(res);

        mockMvc.perform(post("/api/accounts")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("CREDIT_CARD"))
                .andExpect(jsonPath("$.countInLiquidBalance").value(false));
    }

    @Test
    @DisplayName("GET /api/accounts sem token retorna 403")
    void listAccountsWithoutTokenReturns403() throws Exception {
        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/accounts/{id} retorna 204")
    void deleteAccountReturns204() throws Exception {
        mockMvc.perform(delete("/api/accounts/" + UUID.randomUUID())
                        .header("Authorization", token))
                .andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 4: Rodar testes para confirmar que falham**

```bash
./mvnw test -pl . -Dtest=AccountControllerTest -q 2>&1 | tail -10
```
Resultado esperado: FAIL — `AccountController` não existe.

- [ ] **Step 5: Implementar `AccountController.java`**

```java
package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.account.AccountCreateDTO;
import com.fintech.api.dto.account.AccountResponseDTO;
import com.fintech.api.dto.account.AccountUpdateDTO;
import com.fintech.api.openapi.AccountsApi;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AccountController implements AccountsApi {

    private final AccountService accountService;
    private final UserRepository userRepository;

    @Override
    @GetMapping
    public ResponseEntity<List<AccountResponseDTO>> listAccounts() {
        return ResponseEntity.ok(accountService.findAll(getAuthenticatedUser()));
    }

    @Override
    @PostMapping
    public ResponseEntity<AccountResponseDTO> createAccount(@Valid @RequestBody AccountCreateDTO dto) {
        AccountResponseDTO created = accountService.create(dto, getAuthenticatedUser());
        URI uri = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(uri).body(created);
    }

    @Override
    @GetMapping("/{id}")
    public ResponseEntity<AccountResponseDTO> getAccount(@PathVariable UUID id) {
        return ResponseEntity.ok(accountService.findById(id, getAuthenticatedUser()));
    }

    @Override
    @PutMapping("/{id}")
    public ResponseEntity<AccountResponseDTO> updateAccount(
            @PathVariable UUID id, @Valid @RequestBody AccountUpdateDTO dto) {
        return ResponseEntity.ok(accountService.update(id, dto, getAuthenticatedUser()));
    }

    @Override
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAccount(@PathVariable UUID id) {
        accountService.archive(id, getAuthenticatedUser());
        return ResponseEntity.noContent().build();
    }

    private User getAuthenticatedUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
```

- [ ] **Step 6: Deletar `CreditCardController.java`**

```bash
rm src/main/java/com/fintech/api/controller/CreditCardController.java
```

- [ ] **Step 7: Rodar todos os testes**

```bash
./mvnw test -q 2>&1 | tail -15
```
Resultado esperado: todos passam.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(controller): implementa AccountController e remove CreditCardController"
```

---

### Task 10: Atualizar TransactionController

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/controller/TransactionController.java`
- Modify: `backend/src/test/java/com/fintech/api/controller/TransactionControllerTest.java`

- [ ] **Step 1: Atualizar `TransactionControllerTest.java`** — substituir `creditCardId` por `accountId`

Localizar todas as ocorrências de `creditCardId` no arquivo e substituir por `accountId`. O `TransactionRequestDTO` agora exige `accountId` como campo UUID obrigatório.

No teste que cria uma transação via POST, atualizar o DTO para incluir um `accountId`:

```java
UUID accountId = UUID.randomUUID();
TransactionRequestDTO dto = new TransactionRequestDTO(
        "Aluguel", new BigDecimal("1500.00"), LocalDate.now(),
        TransactionType.EXPENSE, TransactionStatus.PENDING,
        1, null, accountId);
```

- [ ] **Step 2: Atualizar `TransactionController.java`**

Localizar e remover qualquer referência a `CreditCardService` ou `CreditCardRepository`. Verificar que `TransactionService` é a única dependência de serviço. O controller não muda estruturalmente — apenas a injeção de `CreditCardService` some (se existia).

- [ ] **Step 3: Rodar todos os testes**

```bash
./mvnw test -q 2>&1 | tail -15
```
Resultado esperado: todos passam.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "fix(controller): atualiza TransactionController para accountId"
```

---

## Track 5 — Frontend

> **Pré-requisito:** `feature/accounts-spec-migration` deve estar merged em `develop`.
> Criar branch a partir do `develop` atualizado.

### Task 11: Regenerar API Client (Orval)

**Branch:** `git checkout -b feature/accounts-frontend develop`

- [ ] **Step 1: Criar branch**

```bash
git checkout -b feature/accounts-frontend develop
```

- [ ] **Step 2: Rodar Orval para gerar cliente accounts**

```bash
cd /home/sergio/fintech-core/frontend
npm run api:generate
```
Resultado esperado: arquivo `src/app/core/api/accounts/accounts.service.ts` criado. Arquivo `src/app/core/api/credit-cards/credit-cards.service.ts` removido ou substituído.

- [ ] **Step 3: Deletar o arquivo de auth regenerado (armadilha conhecida)**

```bash
rm -f src/app/core/api/auth/auth.service.ts
```
(O Orval recria esse arquivo a cada geração — o auth é gerenciado manualmente.)

- [ ] **Step 4: Deletar feature de credit-card**

```bash
rm -rf src/app/features/credit-card/
```

- [ ] **Step 5: Verificar que o projeto compila**

```bash
npm run build 2>&1 | tail -20
```
Resultado esperado: erros apenas em arquivos que ainda referenciam `CreditCardsService` ou `CreditCardResponseDTO` (serão corrigidos nas tasks seguintes).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(frontend): regenera API client — adiciona accounts, remove credit-cards"
```

---

### Task 12: AccountList Component (TDD)

**Files:**
- Create: `frontend/src/app/features/account/account-list/account-list.spec.ts`
- Create: `frontend/src/app/features/account/account-list/account-list.ts`
- Create: `frontend/src/app/features/account/account-list/account-list.html`
- Create: `frontend/src/app/features/account/account-list/account-list.scss`

- [ ] **Step 1: Escrever o teste antes da implementação**

Criar `account-list.spec.ts`:

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { AccountList } from './account-list';
import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatDialogModule } from '@angular/material/dialog';
import { of } from 'rxjs';
import { AccountResponse } from '../../../core/api/fintechSaaSAPI.schemas';

describe('AccountList', () => {
  let accountsService: AccountsService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AccountList, NoopAnimationsModule, MatDialogModule],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });
    accountsService = TestBed.inject(AccountsService);
  });

  it('exibe contas retornadas pelo serviço', async () => {
    const mockAccounts: AccountResponse[] = [
      { id: '1', name: 'Bradesco', type: 'CHECKING', countInLiquidBalance: true,
        countInNetWorth: true, active: true, balance: 1500 }
    ];
    vi.spyOn(accountsService, 'listAccounts').mockReturnValue(of(mockAccounts));

    const fixture = TestBed.createComponent(AccountList);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.accounts()).toHaveLength(1);
    expect(fixture.componentInstance.accounts()[0].name).toBe('Bradesco');
  });

  it('typeLabel retorna rótulo correto para cada tipo', () => {
    const fixture = TestBed.createComponent(AccountList);
    const component = fixture.componentInstance;

    expect(component.typeLabel('CHECKING')).toBe('Conta Corrente');
    expect(component.typeLabel('INVESTMENT')).toBe('Investimento');
    expect(component.typeLabel('CREDIT_CARD')).toBe('Cartão de Crédito');
    expect(component.typeLabel('CASH')).toBe('Carteira');
  });
});
```

- [ ] **Step 2: Rodar testes para confirmar falha**

```bash
cd /home/sergio/fintech-core/frontend
npm test -- --reporter=verbose 2>&1 | grep -A5 "AccountList"
```
Resultado esperado: FAIL — `AccountList` não existe.

- [ ] **Step 3: Criar `account-list.ts`**

```typescript
import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';

import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { AccountResponse } from '../../../core/api/fintechSaaSAPI.schemas';
import { ConfirmationDialogComponent } from '../../../components/confirmation-dialog/confirmation-dialog';

@Component({
  selector: 'app-account-list',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, RouterLink,
    MatTableModule, MatButtonModule, MatIconModule,
    MatChipsModule, MatTooltipModule, MatSnackBarModule, MatDialogModule
  ],
  templateUrl: './account-list.html',
  styleUrl: './account-list.scss'
})
export class AccountList implements OnInit {
  private service = inject(AccountsService);
  private snackBar = inject(MatSnackBar);
  private dialog = inject(MatDialog);

  accounts = signal<AccountResponse[]>([]);
  displayedColumns = ['name', 'type', 'balance', 'flags', 'actions'];

  ngOnInit() { this.load(); }

  load() {
    this.service.listAccounts().subscribe({
      next: (data) => this.accounts.set(data),
      error: () => this.snackBar.open('Erro ao carregar contas.', 'Fechar', { duration: 5000 })
    });
  }

  archive(id: string) {
    const ref = this.dialog.open(ConfirmationDialogComponent, {
      data: { message: 'Deseja arquivar esta conta?' }
    });
    ref.afterClosed().subscribe(confirmed => {
      if (!confirmed) return;
      this.service.deleteAccount(id).subscribe({
        next: () => { this.snackBar.open('Conta arquivada.', 'OK', { duration: 3000 }); this.load(); },
        error: () => this.snackBar.open('Erro ao arquivar conta.', 'Fechar', { duration: 5000 })
      });
    });
  }

  typeLabel(type: string): string {
    const labels: Record<string, string> = {
      CHECKING: 'Conta Corrente',
      INVESTMENT: 'Investimento',
      CREDIT_CARD: 'Cartão de Crédito',
      CASH: 'Carteira'
    };
    return labels[type] ?? type;
  }
}
```

- [ ] **Step 4: Criar `account-list.html`**

```html
<div class="page-header">
  <h1>Contas</h1>
  <a mat-raised-button color="primary" routerLink="/accounts/new">
    <mat-icon>add</mat-icon> Nova Conta
  </a>
</div>

<mat-table [dataSource]="accounts()">
  <ng-container matColumnDef="name">
    <mat-header-cell *matHeaderCellDef>Nome</mat-header-cell>
    <mat-cell *matCellDef="let row">{{ row.name }}</mat-cell>
  </ng-container>

  <ng-container matColumnDef="type">
    <mat-header-cell *matHeaderCellDef>Tipo</mat-header-cell>
    <mat-cell *matCellDef="let row">
      <mat-chip>{{ typeLabel(row.type) }}</mat-chip>
    </mat-cell>
  </ng-container>

  <ng-container matColumnDef="balance">
    <mat-header-cell *matHeaderCellDef>Saldo</mat-header-cell>
    <mat-cell *matCellDef="let row">{{ row.balance | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</mat-cell>
  </ng-container>

  <ng-container matColumnDef="flags">
    <mat-header-cell *matHeaderCellDef>Flags</mat-header-cell>
    <mat-cell *matCellDef="let row">
      <mat-icon [matTooltip]="'Entra no saldo disponível'" *ngIf="row.countInLiquidBalance" color="primary">water_drop</mat-icon>
      <mat-icon [matTooltip]="'Entra no patrimônio'" *ngIf="row.countInNetWorth">account_balance</mat-icon>
    </mat-cell>
  </ng-container>

  <ng-container matColumnDef="actions">
    <mat-header-cell *matHeaderCellDef>Ações</mat-header-cell>
    <mat-cell *matCellDef="let row">
      <a mat-icon-button [routerLink]="['/accounts', row.id]" matTooltip="Editar">
        <mat-icon>edit</mat-icon>
      </a>
      <button mat-icon-button (click)="archive(row.id)" matTooltip="Arquivar">
        <mat-icon>archive</mat-icon>
      </button>
    </mat-cell>
  </ng-container>

  <mat-header-row *matHeaderRowDef="displayedColumns"></mat-header-row>
  <mat-row *matRowDef="let row; columns: displayedColumns;"></mat-row>
</mat-table>
```

- [ ] **Step 5: Criar `account-list.scss`** (vazio por enquanto)

```scss
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
```

- [ ] **Step 6: Rodar testes**

```bash
npm test -- --reporter=verbose 2>&1 | grep -A5 "AccountList"
```
Resultado esperado: `✓ exibe contas retornadas pelo serviço` e `✓ typeLabel retorna rótulo correto`

- [ ] **Step 7: Commit**

```bash
git add src/app/features/account/account-list/
git commit -m "feat(frontend): implementa AccountList com TDD"
```

---

### Task 13: AccountForm Component (TDD)

**Files:**
- Create: `frontend/src/app/features/account/account-form/account-form.spec.ts`
- Create: `frontend/src/app/features/account/account-form/account-form.ts`
- Create: `frontend/src/app/features/account/account-form/account-form.html`
- Create: `frontend/src/app/features/account/account-form/account-form.scss`

- [ ] **Step 1: Escrever o teste antes da implementação**

Criar `account-form.spec.ts`:

```typescript
import { describe, it, expect, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { AccountForm } from './account-form';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

describe('AccountForm', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AccountForm, NoopAnimationsModule],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });
  });

  it('formulário inicia inválido (name e type são required)', () => {
    const fixture = TestBed.createComponent(AccountForm);
    fixture.detectChanges();
    // name inicia vazio → inválido
    fixture.componentInstance.form.patchValue({ name: '' });
    expect(fixture.componentInstance.form.invalid).toBe(true);
  });

  it('formulário válido com name e type preenchidos', () => {
    const fixture = TestBed.createComponent(AccountForm);
    fixture.detectChanges();
    fixture.componentInstance.form.patchValue({ name: 'Bradesco', type: 'CHECKING' });
    expect(fixture.componentInstance.form.valid).toBe(true);
  });

  it('isCreditCard retorna true quando type = CREDIT_CARD', async () => {
    const fixture = TestBed.createComponent(AccountForm);
    fixture.detectChanges();
    fixture.componentInstance.form.patchValue({ type: 'CREDIT_CARD' });
    await fixture.whenStable();
    expect(fixture.componentInstance.isCreditCard()).toBe(true);
  });

  it('isCreditCard retorna false quando type = CHECKING', async () => {
    const fixture = TestBed.createComponent(AccountForm);
    fixture.detectChanges();
    fixture.componentInstance.form.patchValue({ type: 'CHECKING' });
    await fixture.whenStable();
    expect(fixture.componentInstance.isCreditCard()).toBe(false);
  });

  it('ao mudar type para INVESTMENT, countInLiquidBalance vira false automaticamente', async () => {
    const fixture = TestBed.createComponent(AccountForm);
    fixture.detectChanges();
    fixture.componentInstance.form.patchValue({ type: 'INVESTMENT' });
    await fixture.whenStable();
    expect(fixture.componentInstance.form.get('countInLiquidBalance')?.value).toBe(false);
  });
});
```

- [ ] **Step 2: Rodar testes para confirmar falha**

```bash
npm test -- --reporter=verbose 2>&1 | grep -A5 "AccountForm"
```
Resultado esperado: FAIL — `AccountForm` não existe.

- [ ] **Step 3: Criar `account-form.ts`**

```typescript
import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { AccountCreateRequest } from '../../../core/api/fintechSaaSAPI.schemas';

@Component({
  selector: 'app-account-form',
  standalone: true,
  imports: [
    CommonModule, RouterLink, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatButtonModule, MatIconModule, MatSlideToggleModule, MatSnackBarModule
  ],
  templateUrl: './account-form.html',
  styleUrl: './account-form.scss'
})
export class AccountForm implements OnInit {
  private fb = inject(FormBuilder);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private service = inject(AccountsService);
  private snackBar = inject(MatSnackBar);

  saving = signal(false);
  isEditMode = signal(false);
  accountId = signal<string | null>(null);

  readonly accountTypes = [
    { value: 'CHECKING',     label: 'Conta Corrente' },
    { value: 'INVESTMENT',   label: 'Investimento' },
    { value: 'CREDIT_CARD',  label: 'Cartão de Crédito' },
    { value: 'CASH',         label: 'Carteira Física' }
  ];

  readonly cardBrands = ['VISA', 'MASTERCARD', 'ELO', 'AMEX', 'HIPERCARD', 'OTHER'];

  form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    type: ['CHECKING', Validators.required],
    color: [''],
    icon: [''],
    countInLiquidBalance: [true],
    countInNetWorth: [true],
    brand: [''],
    lastFourDigits: ['', [Validators.minLength(4), Validators.maxLength(4)]],
    limitAmount: [null as number | null],
    closingDay: [null as number | null, [Validators.min(1), Validators.max(31)]],
    dueDay: [null as number | null, [Validators.min(1), Validators.max(31)]]
  });

  // toSignal converte o Observable de mudanças do campo em um Signal — correto para Zoneless
  private typeValue = toSignal(this.form.get('type')!.valueChanges, { initialValue: 'CHECKING' });
  isCreditCard = computed(() => this.typeValue() === 'CREDIT_CARD');

  ngOnInit(): void {
    // Atualiza flags de liquidez automaticamente quando o tipo muda
    this.form.get('type')!.valueChanges.subscribe(type => {
      const liquid = type === 'CHECKING' || type === 'CASH';
      this.form.patchValue({ countInLiquidBalance: liquid }, { emitEvent: false });
    });

    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.isEditMode.set(true);
      this.accountId.set(id);
      this.service.getAccount(id).subscribe({
        next: (a) => this.form.patchValue({
          name: a.name, type: a.type, color: a.color ?? '', icon: a.icon ?? '',
          countInLiquidBalance: a.countInLiquidBalance, countInNetWorth: a.countInNetWorth,
          brand: a.creditCardDetails?.brand ?? '',
          lastFourDigits: a.creditCardDetails?.lastFourDigits ?? '',
          limitAmount: a.creditCardDetails?.limitAmount ?? null,
          closingDay: a.creditCardDetails?.closingDay ?? null,
          dueDay: a.creditCardDetails?.dueDay ?? null
        }),
        error: () => {
          this.snackBar.open('Conta não encontrada.', 'Fechar', { duration: 5000 });
          this.router.navigate(['/accounts']);
        }
      });
    }
  }

  onSubmit(): void {
    if (this.form.invalid) return;
    const raw = this.form.getRawValue();
    this.saving.set(true);

    const payload: AccountCreateRequest = {
      name: raw.name!,
      type: raw.type as AccountCreateRequest['type'],
      color: raw.color || undefined,
      icon: raw.icon || undefined,
      countInLiquidBalance: raw.countInLiquidBalance ?? undefined,
      countInNetWorth: raw.countInNetWorth ?? undefined,
      creditCardDetails: raw.type === 'CREDIT_CARD' ? {
        brand: raw.brand as any || undefined,
        lastFourDigits: raw.lastFourDigits || undefined,
        limitAmount: raw.limitAmount ?? undefined,
        closingDay: raw.closingDay ?? undefined,
        dueDay: raw.dueDay ?? undefined
      } : undefined
    };

    const obs$ = this.isEditMode()
      ? this.service.updateAccount(this.accountId()!, payload)
      : this.service.createAccount(payload);

    obs$.subscribe({
      next: () => {
        this.snackBar.open(
          this.isEditMode() ? 'Conta atualizada!' : 'Conta criada!', 'OK', { duration: 3000 });
        this.router.navigate(['/accounts']);
      },
      error: () => {
        this.saving.set(false);
        this.snackBar.open('Erro ao salvar conta.', 'Fechar', { duration: 5000 });
      }
    });
  }
}
```

- [ ] **Step 4: Criar `account-form.html`**

```html
<div class="page-header">
  <h1>{{ isEditMode() ? 'Editar Conta' : 'Nova Conta' }}</h1>
  <a mat-button routerLink="/accounts"><mat-icon>arrow_back</mat-icon> Voltar</a>
</div>

<form [formGroup]="form" (ngSubmit)="onSubmit()" class="form-container">

  <mat-form-field appearance="outline">
    <mat-label>Nome</mat-label>
    <input matInput formControlName="name" placeholder="Ex: Bradesco Corrente">
  </mat-form-field>

  <mat-form-field appearance="outline">
    <mat-label>Tipo</mat-label>
    <mat-select formControlName="type">
      <mat-option *ngFor="let t of accountTypes" [value]="t.value">{{ t.label }}</mat-option>
    </mat-select>
  </mat-form-field>

  <mat-form-field appearance="outline">
    <mat-label>Cor (hex)</mat-label>
    <input matInput formControlName="color" placeholder="#3F51B5">
  </mat-form-field>

  <div class="toggle-row">
    <mat-slide-toggle formControlName="countInLiquidBalance">Entra no saldo disponível do dia</mat-slide-toggle>
    <mat-slide-toggle formControlName="countInNetWorth">Entra no patrimônio total</mat-slide-toggle>
  </div>

  <!-- Campos específicos de cartão — visíveis apenas quando type = CREDIT_CARD -->
  @if (isCreditCard()) {
    <div data-testid="credit-card-fields" class="credit-card-section">
      <h3>Dados do Cartão</h3>

      <mat-form-field appearance="outline">
        <mat-label>Bandeira</mat-label>
        <mat-select formControlName="brand">
          <mat-option *ngFor="let b of cardBrands" [value]="b">{{ b }}</mat-option>
        </mat-select>
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label>Últimos 4 dígitos</mat-label>
        <input matInput formControlName="lastFourDigits" maxlength="4" placeholder="1234">
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label>Limite (R$)</mat-label>
        <input matInput type="number" formControlName="limitAmount" placeholder="5000">
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label>Dia de fechamento</mat-label>
        <input matInput type="number" formControlName="closingDay" min="1" max="31">
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label>Dia de vencimento</mat-label>
        <input matInput type="number" formControlName="dueDay" min="1" max="31">
      </mat-form-field>
    </div>
  }

  <div class="form-actions">
    <button mat-raised-button color="primary" type="submit" [disabled]="form.invalid || saving()">
      {{ saving() ? 'Salvando...' : (isEditMode() ? 'Atualizar' : 'Criar Conta') }}
    </button>
  </div>
</form>
```

- [ ] **Step 5: Criar `account-form.scss`**

```scss
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.form-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 480px;
}

.toggle-row {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.credit-card-section {
  border-left: 3px solid #3F51B5;
  padding-left: 16px;
}

.form-actions {
  margin-top: 8px;
}
```

- [ ] **Step 6: Rodar testes**

```bash
npm test -- --reporter=verbose 2>&1 | grep -A10 "AccountForm"
```
Resultado esperado: todos os 5 testes passam.

- [ ] **Step 7: Commit**

```bash
git add src/app/features/account/account-form/
git commit -m "feat(frontend): implementa AccountForm com TDD e campos condicionais de cartão"
```

---

### Task 14: Atualizar TransactionForm + Rotas

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-form/transaction-form.ts`
- Modify: `frontend/src/app/app.routes.ts`

- [ ] **Step 1: Atualizar `transaction-form.ts`** — substituir creditCard por account

Localizar e substituir as referências a `CreditCardsService` e `CreditCardResponseDTO`:

```typescript
// REMOVER estas importações:
// import { CreditCardsService } from '../../../core/api/credit-cards/credit-cards.service';
// import { CreditCardResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

// ADICIONAR:
import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { AccountResponse } from '../../../core/api/fintechSaaSAPI.schemas';
```

No corpo do componente, substituir:
```typescript
// REMOVER:
private creditCardService = inject(CreditCardsService);
creditCards = signal<CreditCardResponseDTO[]>([]);

// ADICIONAR:
private accountService = inject(AccountsService);
accounts = signal<AccountResponse[]>([]);
```

No `form`, substituir:
```typescript
// REMOVER:
creditCardId: [null as string | null]

// ADICIONAR:
accountId: [null as string | null, Validators.required]
```

No `ngOnInit`, substituir:
```typescript
// REMOVER:
this.creditCardService.listCreditCards().subscribe({
  next: (data) => this.creditCards.set(data),
  error: () => {}
});

// ADICIONAR:
this.accountService.listAccounts().subscribe({
  next: (data) => this.accounts.set(data),
  error: () => {}
});
```

No `payload` dentro de `onSubmit`, substituir:
```typescript
// REMOVER:
type: raw.type as 'INCOME' | 'EXPENSE' | 'TRANSFER',
creditCardId: raw.creditCardId ?? undefined

// ADICIONAR:
type: raw.type as 'INCOME' | 'EXPENSE',
accountId: raw.accountId!
```

- [ ] **Step 2: Atualizar o template `transaction-form.html`**

Localizar o `<mat-select>` de cartões e substituir pelo seletor de contas:

```html
<!-- REMOVER o bloco do creditCardId -->
<!-- ADICIONAR: -->
<mat-form-field appearance="outline">
  <mat-label>Conta *</mat-label>
  <mat-select formControlName="accountId">
    <mat-option *ngFor="let a of accounts()" [value]="a.id">
      {{ a.name }}
    </mat-option>
  </mat-select>
</mat-form-field>
```

Remover também a opção `TRANSFER` do select de `type` (se existir no template).

- [ ] **Step 3: Atualizar `app.routes.ts`** — adicionar rotas de accounts, remover credit-cards

Localizar e remover as rotas de `credit-cards`:
```typescript
// REMOVER:
{
  path: 'credit-cards',
  loadComponent: () => import('./features/credit-card/components/card-list').then(m => m.CardListComponent)
},
{
  path: 'credit-cards/new',
  loadComponent: () => import('./features/credit-card/components/card-form/card-form').then(m => m.CardFormComponent)
},
```

Adicionar as rotas de accounts dentro do bloco `children`:
```typescript
{
  path: 'accounts',
  loadComponent: () => import('./features/account/account-list/account-list').then(m => m.AccountList)
},
{
  path: 'accounts/new',
  loadComponent: () => import('./features/account/account-form/account-form').then(m => m.AccountForm)
},
{
  path: 'accounts/:id',
  loadComponent: () => import('./features/account/account-form/account-form').then(m => m.AccountForm)
},
```

- [ ] **Step 4: Verificar compilação**

```bash
npm run build 2>&1 | tail -20
```
Resultado esperado: sem erros de compilação.

- [ ] **Step 5: Rodar todos os testes frontend**

```bash
npm test 2>&1 | tail -15
```
Resultado esperado: todos os testes passam.

- [ ] **Step 6: Commit final do track**

```bash
git add -A
git commit -m "feat(frontend): atualiza transaction-form e rotas para accounts"
```

---

## Finalização — Merge em develop

Após todos os tracks serem validados individualmente, fazer merge na ordem de dependência:

```bash
# 1. Spec + migration (Track 1)
git checkout develop && git merge --no-ff feature/accounts-spec-migration

# 2. Domain (Track 2)
git merge --no-ff feature/accounts-domain

# 3. Services (Track 3)
git merge --no-ff feature/accounts-service

# 4. Controller (Track 4)
git merge --no-ff feature/accounts-controller

# 5. Frontend (Track 5)
git merge --no-ff feature/accounts-frontend
```

Após merge completo, rodar a suite de testes completa:

```bash
# Backend
cd backend && ./mvnw test -q 2>&1 | tail -10

# Frontend
cd frontend && npm test 2>&1 | tail -10
```

E verificar o banco com a migration Flyway:

```bash
cd backend && ./mvnw spring-boot:run &
# Verificar nos logs: "Successfully applied 1 migration to schema 'public'" para V5
```
