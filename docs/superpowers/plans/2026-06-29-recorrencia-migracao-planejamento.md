# Migração do Planejamento para RecurrenceRule — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Substituir `RecurringBudgetItem` por `RecurrenceRule` como fonte dos itens de planejamento, eliminando a redundância entre os dois sistemas de recorrência.

**Architecture:** Migration SQL big bang converte `recurring_budget_items` → `recurrence_rules` e remove a tabela. `BudgetCycleService.populateRecurringItems()` passa a usar `RecurrenceProjectionService`. A aba "Recorrentes" do planejamento é rewired para chamar os endpoints de `RecurrenceRule` com formulário simplificado que monta a RRULE internamente.

**Tech Stack:** Java 21, Spring Boot 4, Flyway, Angular 21 Zoneless, Orval (cliente API gerado), Vitest, JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-06-29-recorrencia-migracao-planejamento-design.md`

## Global Constraints

- Nunca usar `spring.jpa.hibernate.ddl-auto=update`; toda mudança de schema é via Flyway.
- Migrations aplicadas são imutáveis — nunca editar V13/V16/V19/V20.
- Projeto Angular é Zoneless (`provideZonelessChangeDetection()`); Signals primeiro.
- TypeScript estrito; proibido `any`.
- Nomes de variáveis/classes/métodos em inglês; commits e comentários pedagógicos em PT-BR.
- Nunca incluir `Co-Authored-By` nas mensagens de commit.
- Toda query de negócio escopada pelo tenant — invariante central.
- `BudgetItemResponseDTO` é um record manual em `dto/budget/`; `BudgetItemResponse` no YAML é o espelho de documentação.

---

### Task 1: OpenAPI — atualizar contratos e regenerar clientes

**Files:**
- Modify: `api-spec/openapi.yaml`
- Copy: `backend/src/main/resources/static/openapi.yaml` (cópia do api-spec)
- Regenerate: `backend/target/` (via `./mvnw generate-sources`)
- Regenerate: `frontend/src/app/core/api/` (via `npm run api:generate`)

**Interfaces:**
- Produces: schema `BudgetItemResponse` com `recurrenceRuleId` + `recurrenceOccurrenceDate`; path `PATCH /api/recurrence-rules/{id}/reactivate` com operationId `reactivateRecurrenceRule`; schemas `RecurringBudgetItemRequest` e `RecurringBudgetItemResponse` removidos; paths `/api/recurring-budget-items/**` removidos.

- [ ] **Step 1: Remover schemas RecurringBudgetItem do openapi.yaml**

Localize as linhas dos schemas `RecurringBudgetItemRequest` (em torno de linha 831) e `RecurringBudgetItemResponse` (em torno de linha 855) e remova ambos inteiramente.

- [ ] **Step 2: Remover paths /api/recurring-budget-items do openapi.yaml**

Localize e remova os dois blocos de paths (em torno de linhas 1919–1986):
```yaml
# Remover este bloco inteiro:
  # --- Recurring Budget Items ---

  /api/recurring-budget-items:
    get: ...
    post: ...

  /api/recurring-budget-items/{id}:
    put: ...
    delete: ...
```

- [ ] **Step 3: Adicionar campos ao schema BudgetItemResponse**

Localize `BudgetItemResponse` (em torno de linha 705) e adicione após `installmentGroupId`:
```yaml
        recurrenceRuleId:
          type: string
          format: uuid
          nullable: true
        recurrenceOccurrenceDate:
          type: string
          format: date
          nullable: true
```

- [ ] **Step 4: Adicionar path PATCH /api/recurrence-rules/{id}/reactivate**

Adicione após o bloco de `delete` de `/api/recurrence-rules/{id}` (após linha ~2075):
```yaml
  /api/recurrence-rules/{id}/reactivate:
    patch:
      tags: [recurrence-rules]
      operationId: reactivateRecurrenceRule
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '200':
          description: Regra reativada
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/RecurrenceRuleResponseDTO'
        '422':
          description: Regra já está ativa
```

- [ ] **Step 5: Copiar spec para o static do backend**

```bash
cp api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml
```

- [ ] **Step 6: Regenerar interfaces do backend**

```bash
cd backend && ./mvnw generate-sources -q
```

Esperado: saída vazia (sem erros). Se houver erros de compilação, verificar o YAML (indentação, referências quebradas).

- [ ] **Step 7: Regenerar cliente Orval do frontend**

```bash
cd frontend && npm run api:generate
```

Esperado: arquivos em `src/app/core/api/budget/` e `src/app/core/api/recurrence-rules/` atualizados. Verificar que `recurrence-rules.service.ts` ganhou o método `reactivateRecurrenceRule`.

```bash
grep "reactivateRecurrenceRule" frontend/src/app/core/api/recurrence-rules/recurrence-rules.service.ts
```

Esperado: 1+ linhas com a função.

- [ ] **Step 8: Commit**

```bash
git add api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml \
        frontend/src/app/core/api/
git commit -m "feat(recorrencia): atualiza openapi — remove recurring-budget-items, adiciona reactivate e campos de recorrência em BudgetItemResponse"
```

---

### Task 2: Flyway V21 + seed_base.sql

**Files:**
- Create: `backend/src/main/resources/db/migration/V21__migrate_recurring_budget_items_to_recurrence_rules.sql`
- Modify: `backend/src/test/resources/sql/seed_base.sql`

**Interfaces:**
- Produces: tabela `recurring_budget_items` removida; `budget_items` com colunas `recurrence_rule_id` e `recurrence_occurrence_date`; `seed_base.sql` com 1 regra de recorrência para testes de integração.

- [ ] **Step 1: Criar migration V21**

Crie o arquivo `backend/src/main/resources/db/migration/V21__migrate_recurring_budget_items_to_recurrence_rules.sql` com o conteúdo:

```sql
-- V21: Migra recurring_budget_items → recurrence_rules (big bang).
-- Executa APÓS V20 (seed dev), que popula recurring_budget_items com dados da Família Costa.
-- Após esta migration, a tabela recurring_budget_items não existe mais.

-- 1. Cria RecurrenceRule equivalente para cada RecurringBudgetItem.
--    BYMONTHDAY={day_of_month} é o equivalente RRULE de "todo dia N do mês".
INSERT INTO recurrence_rules (
    id, tenant_id, description, base_amount, type,
    category_id, account_id, rrule, start_date, status,
    created_by, created_at, updated_at
)
SELECT
    gen_random_uuid(),
    tenant_id,
    description,
    amount,
    type,
    category_id,
    account_id,
    'FREQ=MONTHLY;BYMONTHDAY=' || day_of_month,
    CURRENT_DATE,
    CASE WHEN active THEN 'ACTIVE' ELSE 'CANCELLED' END,
    created_by,
    created_at,
    updated_at
FROM recurring_budget_items;

-- 2. Adiciona colunas novas em budget_items.
ALTER TABLE budget_items
    ADD COLUMN recurrence_rule_id UUID REFERENCES recurrence_rules(id),
    ADD COLUMN recurrence_occurrence_date DATE;

-- 3. Mapeia BudgetItems existentes para as novas RecurrenceRules.
--    Chave de ligação: recurring_item_id → tenant + description + type + base_amount.
--    Funciona para o dataset controlado (sem duplicatas de descrição/tipo/valor por tenant).
UPDATE budget_items bi
SET recurrence_rule_id = rr.id
FROM recurring_budget_items rbi
JOIN recurrence_rules rr
    ON rr.tenant_id = rbi.tenant_id
   AND rr.description = rbi.description
   AND rr.type = rbi.type
   AND rr.base_amount = rbi.amount
WHERE bi.recurring_item_id = rbi.id;

-- 4. Remove coluna legada e tabela.
ALTER TABLE budget_items DROP COLUMN recurring_item_id;
DROP TABLE recurring_budget_items;

-- 5. Índice de suporte para queries de itens por regra.
CREATE INDEX idx_budget_items_recurrence_rule ON budget_items(recurrence_rule_id);
```

- [ ] **Step 2: Adicionar RecurrenceRule mínima ao seed_base.sql**

O `seed_base.sql` não tem `recurring_budget_items` atualmente. Adicione uma `recurrence_rules` para suportar os testes de integração de abertura de ciclo. Adicione antes do `END $$;` final:

```sql
  -- UUID de referência para a regra de recorrência nos testes
  v_rule UUID := 'eeeeeeee-0000-0000-0000-000000000001';
```

Na seção de declarações (bloco `DECLARE`), acrescente `v_rule UUID := 'eeeeeeee-0000-0000-0000-000000000001';` após as declarações existentes.

Depois, antes de `END $$;`, adicione:

```sql
  INSERT INTO recurrence_rules (id, tenant_id, description, base_amount, type,
      account_id, rrule, start_date, status, created_at, updated_at)
  VALUES (v_rule, v_tenant, 'Salário Fixo', 5000.00, 'INCOME',
      v_chk, 'FREQ=MONTHLY;BYMONTHDAY=5', '2026-01-05', 'ACTIVE', now(), now());
```

- [ ] **Step 3: Verificar que o backend inicia com as migrations**

```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev &
sleep 15
curl -s http://localhost:8080/actuator/health | grep '"status":"UP"'
kill %1
```

Esperado: `"status":"UP"`. Se falhar, verificar logs — provavelmente erro de SQL na V21.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/migration/V21__migrate_recurring_budget_items_to_recurrence_rules.sql \
        backend/src/test/resources/sql/seed_base.sql
git commit -m "feat(recorrencia): adiciona V21 — migra recurring_budget_items para recurrence_rules e atualiza seed_base"
```

---

### Task 3: Backend — remover artefatos legados + atualizar BudgetItem + BudgetItemResponseDTO

**Files:**
- Delete: `backend/src/main/java/com/fintech/api/domain/budget/RecurringBudgetItem.java`
- Delete: `backend/src/main/java/com/fintech/api/service/RecurringBudgetItemService.java`
- Delete: `backend/src/main/java/com/fintech/api/controller/RecurringBudgetItemController.java`
- Delete: `backend/src/main/java/com/fintech/api/repository/RecurringBudgetItemRepository.java`
- Delete: `backend/src/main/java/com/fintech/api/dto/budget/RecurringBudgetItemRequest.java`
- Delete: `backend/src/main/java/com/fintech/api/dto/budget/RecurringBudgetItemResponseDTO.java`
- Modify: `backend/src/main/java/com/fintech/api/domain/budget/BudgetItem.java`
- Modify: `backend/src/main/java/com/fintech/api/dto/budget/BudgetItemResponseDTO.java`

**Interfaces:**
- Produces: `BudgetItem` com campos `recurrenceRule: RecurrenceRule` e `recurrenceOccurrenceDate: LocalDate`; `BudgetItemResponseDTO` com `recurrenceRuleId: UUID` e `recurrenceOccurrenceDate: LocalDate`; todos os 6 artefatos de RecurringBudgetItem removidos.

- [ ] **Step 1: Deletar os 6 arquivos legados**

```bash
rm backend/src/main/java/com/fintech/api/domain/budget/RecurringBudgetItem.java
rm backend/src/main/java/com/fintech/api/service/RecurringBudgetItemService.java
rm backend/src/main/java/com/fintech/api/controller/RecurringBudgetItemController.java
rm backend/src/main/java/com/fintech/api/repository/RecurringBudgetItemRepository.java
rm backend/src/main/java/com/fintech/api/dto/budget/RecurringBudgetItemRequest.java
rm backend/src/main/java/com/fintech/api/dto/budget/RecurringBudgetItemResponseDTO.java
```

- [ ] **Step 2: Atualizar BudgetItem.java — trocar recurringItem por recurrenceRule**

Em `BudgetItem.java`, substitua o bloco do campo `recurringItem`:

```java
// REMOVER:
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "recurring_item_id")
@ToString.Exclude
private RecurringBudgetItem recurringItem;
```

Por:

```java
// ADICIONAR:
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "recurrence_rule_id")
@ToString.Exclude
private RecurrenceRule recurrenceRule;

@Column(name = "recurrence_occurrence_date")
private LocalDate recurrenceOccurrenceDate;
```

Também remova o import de `RecurringBudgetItem` e adicione os imports necessários:
```java
import com.fintech.api.domain.recurrence.RecurrenceRule;
// LocalDate já deve estar importado; se não estiver, adicione:
import java.time.LocalDate;
```

- [ ] **Step 3: Atualizar BudgetItemResponseDTO.java — adicionar recurrenceRuleId e recurrenceOccurrenceDate**

Substitua o record inteiro pelo conteúdo abaixo (adiciona dois campos ao final):

```java
package com.fintech.api.dto.budget;

import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetItemSource;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BudgetItemResponseDTO(
    UUID id,
    String description,
    BigDecimal amount,
    TransactionType type,
    LocalDate expectedDate,
    BudgetItemSource source,
    BudgetItemStatus status,
    UUID categoryId,
    String categoryName,
    UUID accountId,
    String accountName,
    UUID transactionId,
    TransactionStatus transactionStatus,
    UUID installmentGroupId,
    UUID recurrenceRuleId,
    LocalDate recurrenceOccurrenceDate
) {
    public static BudgetItemResponseDTO fromEntity(BudgetItem item) {
        return new BudgetItemResponseDTO(
            item.getId(),
            item.getDescription(),
            item.getAmount(),
            item.getType(),
            item.getExpectedDate(),
            item.getSource(),
            item.getStatus(),
            item.getCategory() != null ? item.getCategory().getId() : null,
            item.getCategory() != null ? item.getCategory().getName() : null,
            item.getAccount() != null ? item.getAccount().getId() : null,
            item.getAccount() != null ? item.getAccount().getName() : null,
            item.getTransaction() != null ? item.getTransaction().getId() : null,
            item.getTransaction() != null ? item.getTransaction().getStatus() : null,
            item.getInstallmentGroup() != null ? item.getInstallmentGroup().getId() : null,
            item.getRecurrenceRule() != null ? item.getRecurrenceRule().getId() : null,
            item.getRecurrenceOccurrenceDate()
        );
    }
}
```

- [ ] **Step 4: Verificar que o código compila**

```bash
cd backend && ./mvnw compile -q
```

Esperado: saída vazia. Se houver erros de import (ex: `BudgetCycleService` ainda referencia `RecurringBudgetItem`), serão corrigidos na Task 4.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(recorrencia): remove artefatos RecurringBudgetItem e atualiza BudgetItem + BudgetItemResponseDTO"
```

---

### Task 4: Backend — BudgetCycleService rewired + testes unitários atualizados

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/BudgetCycleService.java`
- Modify: `backend/src/test/java/com/fintech/api/service/BudgetCycleServiceTest.java`

**Interfaces:**
- Consumes: `RecurrenceProjectionService.project(Tenant, LocalDate, LocalDate): List<ProjectedOccurrence>` (de `service/recurrence/`); `ProjectedOccurrence` record com campos `ruleId()`, `occurrenceDate()`, `description()`, `amount()`, `type()`, `categoryId()`, `accountId()`.
- Produces: `BudgetCycleService.open()` cria `BudgetItem` com `recurrenceRule` e `recurrenceOccurrenceDate` preenchidos para cada ocorrência projetada no período.

- [ ] **Step 1: Escrever o teste que vai falhar — open() com regra projetada**

Em `BudgetCycleServiceTest.java`:

1. Remova o `@Mock RecurringBudgetItemRepository recurringRepository` e o import correspondente.
2. Adicione os novos mocks:

```java
@Mock RecurrenceProjectionService recurrenceProjectionService;
@Mock RecurrenceRuleRepository ruleRepository;
@Mock CategoryRepository categoryRepository;
```

3. Remova os dois testes de `calculateExpectedDate` (o método será removido do service):
   - `calculateExpectedDate_dayGeStartDay()`
   - `calculateExpectedDate_dayLtStartDay()`

4. No teste `open_calculaOpeningBalance()`, substitua o stub do `recurringRepository`:
```java
// REMOVER:
when(recurringRepository.findAllByTenantAndActiveTrueOrderByDayOfMonthAscDescriptionAsc(tenant))
    .thenReturn(List.of());

// ADICIONAR:
when(recurrenceProjectionService.project(eq(tenant), any(LocalDate.class), any(LocalDate.class)))
    .thenReturn(List.of());
```

5. Adicione um novo teste após `open_calculaOpeningBalance()`:

```java
@Test
@DisplayName("open() com regra projetada → cria BudgetItem com recurrenceRule e occurrenceDate preenchidos")
void open_comRegra_criaBudgetItemComRecurrenceRuleId() {
    Tenant tenant = tenantWith(1);
    User user = new User();
    UUID ruleId = UUID.randomUUID();

    when(cycleRepository.findByTenantAndStatus(tenant, BudgetCycleStatus.OPEN))
        .thenReturn(Optional.empty());
    when(cycleRepository.existsOverlap(any(), any(), any())).thenReturn(false);
    when(accountRepository.sumLiquidBalanceByTenant(any(), any(), any(), any()))
        .thenReturn(BigDecimal.ZERO);
    when(cycleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    // Regra projeta 1 ocorrência no dia 10/06/2026
    var occurrence = new com.fintech.api.service.recurrence.ProjectedOccurrence(
        ruleId, LocalDate.of(2026, 6, 10),
        "Salário", new BigDecimal("5000.00"),
        com.fintech.api.domain.enums.TransactionType.INCOME,
        null, null, null,
        UUID.randomUUID(), "Conta Corrente"
    );
    when(recurrenceProjectionService.project(eq(tenant), any(), any()))
        .thenReturn(List.of(occurrence));

    var ruleProxy = RecurrenceRule.builder().id(ruleId).build();
    when(ruleRepository.getReferenceById(ruleId)).thenReturn(ruleProxy);
    when(transactionRepository.findInstallmentsByTenantAndInvoiceMonth(any(), anyInt(), anyInt(), any()))
        .thenReturn(List.of());

    var itemCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
    when(itemRepository.saveAll(itemCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

    service.open(tenant, user, "2026-06");

    @SuppressWarnings("unchecked")
    var saved = (java.util.List<BudgetItem>) itemCaptor.getValue();
    assertThat(saved).hasSize(1);
    BudgetItem item = saved.get(0);
    assertThat(item.getRecurrenceRule().getId()).isEqualTo(ruleId);
    assertThat(item.getRecurrenceOccurrenceDate()).isEqualTo(LocalDate.of(2026, 6, 10));
    assertThat(item.getSource()).isEqualTo(com.fintech.api.domain.enums.BudgetItemSource.RECURRING);
}
```

- [ ] **Step 2: Rodar o teste para confirmar que falha**

```bash
cd backend && ./mvnw test -Dtest=BudgetCycleServiceTest -q 2>&1 | tail -20
```

Esperado: falha de compilação ou `NoSuchMethodError` relacionado a `recurringRepository` — confirma que o teste está apontando para o código legado.

- [ ] **Step 3: Reescrever BudgetCycleService**

Substitua os imports e a definição de campos no início da classe:

**Remover imports:**
```java
import com.fintech.api.domain.budget.RecurringBudgetItem;
import com.fintech.api.repository.RecurringBudgetItemRepository;
```

**Adicionar imports:**
```java
import com.fintech.api.domain.recurrence.RecurrenceRule;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.RecurrenceRuleRepository;
import com.fintech.api.service.recurrence.ProjectedOccurrence;
import com.fintech.api.service.recurrence.RecurrenceProjectionService;
```

**Substituir o campo `recurringRepository`:**
```java
// REMOVER:
private final RecurringBudgetItemRepository recurringRepository;

// ADICIONAR:
private final RecurrenceProjectionService recurrenceProjectionService;
private final RecurrenceRuleRepository ruleRepository;
private final CategoryRepository categoryRepository;
```

**Atualizar chamada em `open()` — substituir `populateRecurringItems(cycle, tenant, user, startDate, startDay)` por:**
```java
populateRecurringItems(cycle, tenant, user, startDate, endDate);
```

**Substituir o método `populateRecurringItems` inteiro:**
```java
private void populateRecurringItems(BudgetCycle cycle, Tenant tenant, User user,
                                    LocalDate startDate, LocalDate endDate) {
    List<ProjectedOccurrence> projected =
        recurrenceProjectionService.project(tenant, startDate, endDate);
    if (projected.isEmpty()) return;

    List<BudgetItem> items = projected.stream()
        .map(p -> BudgetItem.builder()
            .cycle(cycle)
            .tenant(tenant)
            .description(p.description())
            .amount(p.amount())
            .type(p.type())
            .category(p.categoryId() != null
                ? categoryRepository.getReferenceById(p.categoryId())
                : null)
            .account(accountRepository.getReferenceById(p.accountId()))
            .expectedDate(p.occurrenceDate())
            .source(BudgetItemSource.RECURRING)
            .recurrenceRule(ruleRepository.getReferenceById(p.ruleId()))
            .recurrenceOccurrenceDate(p.occurrenceDate())
            .createdBy(user)
            .build())
        .toList();

    itemRepository.saveAll(items);
}
```

**Remover o método `calculateExpectedDate`** (não é mais usado):
```java
// REMOVER este método inteiro:
LocalDate calculateExpectedDate(LocalDate cycleStartDate, int startDay, int dayOfMonth) { ... }
```

- [ ] **Step 4: Rodar os testes para confirmar que passam**

```bash
cd backend && ./mvnw test -Dtest=BudgetCycleServiceTest -q
```

Esperado: `BUILD SUCCESS`. Se falhar, verificar se os imports novos foram adicionados.

- [ ] **Step 5: Rodar a suite completa para verificar regressões**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -30
```

Esperado: `BUILD SUCCESS`. Se houver falhas em outros testes que referenciam `RecurringBudgetItemRepository` (ex: testes do controller), corrigi-los agora removendo as referências.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/BudgetCycleService.java \
        backend/src/test/java/com/fintech/api/service/BudgetCycleServiceTest.java
git commit -m "feat(recorrencia): rewire BudgetCycleService para usar RecurrenceProjectionService na abertura do ciclo"
```

---

### Task 5: Backend — RecurrenceRuleService.reactivate() + controller + testes

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/RecurrenceRuleService.java`
- Modify: `backend/src/main/java/com/fintech/api/controller/RecurrenceRulesController.java`
- Modify: `backend/src/test/java/com/fintech/api/service/RecurrenceRuleServiceTest.java`
- Modify: `backend/src/test/java/com/fintech/api/controller/RecurrenceRulesControllerTest.java`

**Interfaces:**
- Consumes: `RecurrenceRuleRepository.findByIdAndTenant(UUID, Tenant): Optional<RecurrenceRule>` (já existe); `RecurrenceRuleResponseDTO.fromEntity(RecurrenceRule)` (já existe).
- Produces: `RecurrenceRuleService.reactivate(UUID id, User user): RecurrenceRuleResponseDTO`; `PATCH /api/recurrence-rules/{id}/reactivate` retorna 200 com `RecurrenceRuleResponseDTO`.

- [ ] **Step 1: Escrever testes para reactivate no RecurrenceRuleServiceTest**

Adicione ao final da classe `RecurrenceRuleServiceTest`, antes do `}` final:

```java
@Test
@DisplayName("reactivate() em regra CANCELLED → retorna DTO com status ACTIVE")
void reactivate_regraActiva_retornaActive() {
    RecurrenceRule cancelledRule = rule().toBuilder()
            .status(RecurrenceStatus.CANCELLED).build();

    when(repository.findByIdAndTenant(ruleId, user.getTenant()))
            .thenReturn(Optional.of(cancelledRule));
    when(repository.save(cancelledRule)).thenReturn(cancelledRule);

    RecurrenceRuleResponseDTO result = service.reactivate(ruleId, user);

    assertThat(result.status()).isEqualTo(RecurrenceStatus.ACTIVE);
    verify(repository).save(cancelledRule);
}

@Test
@DisplayName("reactivate() em regra ACTIVE → lança IllegalStateException")
void reactivate_regraJaActive_lancaException() {
    RecurrenceRule activeRule = rule().toBuilder()
            .status(RecurrenceStatus.ACTIVE).build();

    when(repository.findByIdAndTenant(ruleId, user.getTenant()))
            .thenReturn(Optional.of(activeRule));

    assertThatThrownBy(() -> service.reactivate(ruleId, user))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("A regra já está ativa.");
}

@Test
@DisplayName("reactivate() com ID inexistente → EntityNotFoundException")
void reactivate_regraInexistente_lancaNotFound() {
    when(repository.findByIdAndTenant(ruleId, user.getTenant()))
            .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.reactivate(ruleId, user))
            .isInstanceOf(EntityNotFoundException.class);
}
```

- [ ] **Step 2: Rodar para confirmar que falham**

```bash
cd backend && ./mvnw test -Dtest=RecurrenceRuleServiceTest -q 2>&1 | tail -10
```

Esperado: falha — `reactivate` ainda não existe no service.

- [ ] **Step 3: Implementar RecurrenceRuleService.reactivate()**

Adicione após o método `cancel()` em `RecurrenceRuleService.java`:

```java
@Transactional
public RecurrenceRuleResponseDTO reactivate(UUID id, User user) {
    RecurrenceRule rule = findOwned(id, user);
    if (rule.getStatus() == RecurrenceStatus.ACTIVE) {
        throw new IllegalStateException("A regra já está ativa.");
    }
    rule.setStatus(RecurrenceStatus.ACTIVE);
    return RecurrenceRuleResponseDTO.fromEntity(repository.save(rule));
}
```

- [ ] **Step 4: Rodar para confirmar que passam**

```bash
cd backend && ./mvnw test -Dtest=RecurrenceRuleServiceTest -q
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 5: Escrever teste do controller para reactivate**

Em `RecurrenceRulesControllerTest.java`, adicione após os testes existentes:

```java
@Test
@DisplayName("PATCH /api/recurrence-rules/{id}/reactivate → 200 com regra reativada")
void reactivate_regaCancelada_retorna200() throws Exception {
    // Campos do record: id, description, baseAmount, type, categoryId, categoryName,
    //                   accountId, accountName, rrule, startDate, status
    RecurrenceRuleResponseDTO dto = new RecurrenceRuleResponseDTO(
        UUID.randomUUID(), "Netflix", new java.math.BigDecimal("50.00"),
        com.fintech.api.domain.enums.TransactionType.EXPENSE,
        null, null, UUID.randomUUID(), "Conta Corrente",
        "FREQ=MONTHLY;BYMONTHDAY=15",
        java.time.LocalDate.of(2026, 1, 1),
        RecurrenceStatus.ACTIVE
    );
    when(service.reactivate(any(UUID.class), any())).thenReturn(dto);

    mockMvc.perform(patch("/api/recurrence-rules/{id}/reactivate", UUID.randomUUID())
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
}
```

- [ ] **Step 6: Implementar o endpoint no controller**

Em `RecurrenceRulesController.java`, adicione após o método `cancelRecurrenceRule()`:

```java
@Override
@PatchMapping("/{id}/reactivate")
public ResponseEntity<RecurrenceRuleResponseDTO> reactivateRecurrenceRule(@PathVariable UUID id) {
    return ResponseEntity.ok(service.reactivate(id, getAuthenticatedUser()));
}
```

- [ ] **Step 7: Rodar testes do controller**

```bash
cd backend && ./mvnw test -Dtest=RecurrenceRulesControllerTest -q
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 8: Rodar suite completa**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -10
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/RecurrenceRuleService.java \
        backend/src/main/java/com/fintech/api/controller/RecurrenceRulesController.java \
        backend/src/test/java/com/fintech/api/service/RecurrenceRuleServiceTest.java \
        backend/src/test/java/com/fintech/api/controller/RecurrenceRulesControllerTest.java
git commit -m "feat(recorrencia): adiciona endpoint PATCH /recurrence-rules/{id}/reactivate"
```

---

### Task 6: Frontend — rewire aba "Recorrentes" do planejamento

**Files:**
- Modify: `frontend/src/app/features/planning/planning.service.ts`
- Modify: `frontend/src/app/features/planning/recurring-item-form/recurring-item-form.ts`
- Modify: `frontend/src/app/features/planning/recurring-item-form/recurring-item-form.html`
- Modify: `frontend/src/app/features/planning/recurring-item-list/recurring-item-list.ts`

**Interfaces:**
- Consumes: `RecurrenceRulesService` (de `core/api/recurrence-rules/recurrence-rules.service.ts`) com métodos `listRecurrenceRules()`, `createRecurrenceRule(dto)`, `patchRecurrenceRule(id, dto)`, `cancelRecurrenceRule(id)`, `reactivateRecurrenceRule(id)`.
- Consumes: `RecurrenceRuleResponseDTO`, `RecurrenceRuleCreateDTO`, `RecurrenceRulePatchDTO` (de `core/api/fintechSaaSAPI.schemas.ts`).
- Produces: `PlanningService` com os 5 métodos de recorrência rewired; formulário simplificado que monta RRULE internamente; lista que lê `baseAmount` e extrai dia do `rrule`.

- [ ] **Step 1: Atualizar planning.service.ts**

Substitua o conteúdo inteiro das linhas que tratam de recurring (imports e métodos) pelo seguinte:

**Nos imports, substitua:**
```typescript
// REMOVER:
import {
  ...
  RecurringBudgetItemRequest,
  RecurringBudgetItemResponse,
  ...
} from '../../core/api/fintechSaaSAPI.schemas';

// ADICIONAR (ao bloco de imports de schemas, mantendo os outros):
import {
  ...
  RecurrenceRuleCreateDTO,
  RecurrenceRulePatchDTO,
  RecurrenceRuleResponseDTO,
  ...
} from '../../core/api/fintechSaaSAPI.schemas';
```

**Adicionar o inject do novo serviço (após `private readonly budget`):**
```typescript
private readonly recurrenceRules = inject(RecurrenceRulesService);
```

**Adicionar o import do serviço gerado no topo do arquivo:**
```typescript
import { RecurrenceRulesService } from '../../core/api/recurrence-rules/recurrence-rules.service';
```

**Substituir os 5 métodos de recurring:**
```typescript
listRecurring(): Observable<RecurrenceRuleResponseDTO[]> {
  return this.recurrenceRules.listRecurrenceRules();
}

createRecurring(req: RecurrenceRuleCreateDTO): Observable<RecurrenceRuleResponseDTO> {
  return this.recurrenceRules.createRecurrenceRule(req);
}

updateRecurring(id: string, req: RecurrenceRulePatchDTO): Observable<RecurrenceRuleResponseDTO> {
  return this.recurrenceRules.patchRecurrenceRule(id, req);
}

deleteRecurring(id: string): Observable<void> {
  return this.recurrenceRules.cancelRecurrenceRule(id);
}

reactivateRecurring(id: string): Observable<RecurrenceRuleResponseDTO> {
  return this.recurrenceRules.reactivateRecurrenceRule(id);
}
```

> **Note:** Remova o `private readonly http = inject(HttpClient)` se não for mais usado por nenhum outro método do service. Verifique os demais métodos antes de remover.

- [ ] **Step 2: Atualizar recurring-item-list.ts**

Substitua o arquivo inteiro:

```typescript
import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { filter, finalize, switchMap } from 'rxjs';

import { PlanningService } from '../planning.service';
import { RecurrenceRuleCreateDTO, RecurrenceRulePatchDTO, RecurrenceRuleResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';
import { RecurringItemFormComponent } from '../recurring-item-form/recurring-item-form';

@Component({
  selector: 'app-recurring-item-list',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe,
    MatButtonModule, MatIconModule, MatSlideToggleModule, MatSnackBarModule,
    MatTableModule, MatTooltipModule,
  ],
  templateUrl: './recurring-item-list.html',
  styleUrl: './recurring-item-list.scss',
})
export class RecurringItemList implements OnInit {
  private readonly planningService = inject(PlanningService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  readonly items = signal<RecurrenceRuleResponseDTO[]>([]);
  readonly inactiveItems = signal<RecurrenceRuleResponseDTO[]>([]);
  readonly loading = signal(true);
  readonly showInactive = signal(false);

  displayedColumns = ['day', 'description', 'type', 'amount', 'actions'];

  ngOnInit(): void {
    this.load();
  }

  /** Extrai o dia do mês da string RRULE. Ex: "FREQ=MONTHLY;BYMONTHDAY=15" → "15". */
  dayFromRrule(rrule: string | null | undefined): string {
    if (!rrule) return '?';
    const match = rrule.match(/BYMONTHDAY=(-?\d+)/);
    return match ? (match[1] === '-1' ? 'último' : match[1]) : '?';
  }

  private load(): void {
    this.planningService.listRecurring()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: all => {
          this.items.set(all.filter(i => i.status === 'ACTIVE'));
          this.inactiveItems.set(all.filter(i => i.status !== 'ACTIVE'));
        },
        error: () => this.snackBar.open('Erro ao carregar recorrências.', 'OK', { duration: 3000 }),
      });
  }

  openForm(existing?: RecurrenceRuleResponseDTO): void {
    const ref = this.dialog.open(RecurringItemFormComponent, {
      width: '500px',
      data: existing ?? null,
    });
    ref.afterClosed().pipe(
      filter(Boolean),
      switchMap((result: RecurrenceRuleCreateDTO | RecurrenceRulePatchDTO) => existing
        ? this.planningService.updateRecurring(existing.id!, result as RecurrenceRulePatchDTO)
        : this.planningService.createRecurring(result as RecurrenceRuleCreateDTO)
      )
    ).subscribe({
      next: () => {
        this.load();
        this.snackBar.open(existing ? 'Recorrência atualizada.' : 'Recorrência criada.', 'OK', { duration: 2000 });
      },
      error: () => this.snackBar.open('Erro ao salvar recorrência.', 'OK', { duration: 3000 }),
    });
  }

  deactivate(item: RecurrenceRuleResponseDTO): void {
    this.planningService.deleteRecurring(item.id!).subscribe({
      next: () => {
        this.items.update(list => list.filter(i => i.id !== item.id));
        this.inactiveItems.update(list => [...list, { ...item, status: 'CANCELLED' }]);
        this.snackBar.open('Recorrência cancelada.', 'OK', { duration: 2000 });
      },
    });
  }

  reactivate(item: RecurrenceRuleResponseDTO): void {
    this.planningService.reactivateRecurring(item.id!).subscribe({
      next: (reactivated: RecurrenceRuleResponseDTO) => {
        this.inactiveItems.update(list => list.filter(i => i.id !== item.id));
        this.items.update(list => [...list, reactivated]);
        this.snackBar.open('Recorrência reativada.', 'OK', { duration: 2000 });
      },
      error: () => this.snackBar.open('Erro ao reativar recorrência.', 'OK', { duration: 3000 }),
    });
  }
}
```

- [ ] **Step 3: Atualizar recurring-item-list.html**

No template, localize onde a coluna "day" exibe `item.dayOfMonth` e substitua pela chamada ao método helper:

```html
<!-- ANTES (onde aparece item.dayOfMonth): -->
{{ item.dayOfMonth }}

<!-- DEPOIS: -->
{{ dayFromRrule(item.rrule) }}
```

Onde aparece `item.amount`, substitua por `item.baseAmount`:
```html
<!-- ANTES: -->
{{ item.amount | currency:'BRL' }}

<!-- DEPOIS: -->
{{ item.baseAmount | currency:'BRL' }}
```

Se o template usa `item.active` para qualquer lógica visual, substitua por `item.status === 'ACTIVE'`.

- [ ] **Step 4: Atualizar recurring-item-form.ts**

Substitua o arquivo inteiro:

```typescript
import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { provideNativeDateAdapter } from '@angular/material/core';

import {
  RecurrenceRuleCreateDTO,
  RecurrenceRulePatchDTO,
  RecurrenceRuleResponseDTO,
} from '../../../core/api/fintechSaaSAPI.schemas';
import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { CategoriesService } from '../../../core/api/categories/categories.service';
import { toSignal } from '@angular/core/rxjs-interop';

@Component({
  selector: 'app-recurring-item-form',
  standalone: true,
  providers: [provideNativeDateAdapter()],
  imports: [
    CommonModule, ReactiveFormsModule,
    MatButtonModule, MatDialogModule, MatFormFieldModule,
    MatInputModule, MatSelectModule, MatDatepickerModule,
  ],
  templateUrl: './recurring-item-form.html',
})
export class RecurringItemFormComponent {
  private readonly fb = inject(FormBuilder);
  private readonly dialogRef = inject(MatDialogRef<RecurringItemFormComponent>);
  private readonly accountsService = inject(AccountsService);
  private readonly categoriesService = inject(CategoriesService);

  readonly existing: RecurrenceRuleResponseDTO | null = inject(MAT_DIALOG_DATA, { optional: true });

  readonly accounts = toSignal(this.accountsService.getAccounts(), { initialValue: [] });
  readonly categories = toSignal(this.categoriesService.getCategories(), { initialValue: [] });

  /** Em modo de edição, só description e baseAmount são editáveis (escopo do PATCH). */
  readonly isEditMode = !!this.existing;

  readonly form = this.fb.group({
    description: [this.existing?.description ?? '', Validators.required],
    baseAmount: [this.existing?.baseAmount ?? null as number | null,
      [Validators.required, Validators.min(0.01)]],
    type: [{ value: this.existing?.type ?? 'EXPENSE', disabled: this.isEditMode }, Validators.required],
    dayOfMonth: [
      { value: this.dayFromRrule(this.existing?.rrule), disabled: this.isEditMode },
      [Validators.required, Validators.min(1), Validators.max(28)]
    ],
    accountId: [
      { value: this.existing?.accountId ?? null as string | null, disabled: this.isEditMode },
      Validators.required
    ],
    categoryId: [
      { value: this.existing?.categoryId ?? null as string | null, disabled: this.isEditMode }
    ],
    startDate: [
      { value: this.defaultStartDate(), disabled: this.isEditMode },
      Validators.required
    ],
  });

  onSubmit(): void {
    if (this.form.invalid) return;
    const v = this.form.getRawValue();

    if (this.isEditMode) {
      const patch: RecurrenceRulePatchDTO = {
        description: v.description ?? undefined,
        baseAmount: v.baseAmount ?? undefined,
      };
      this.dialogRef.close(patch);
    } else {
      const create: RecurrenceRuleCreateDTO = {
        description: v.description!,
        baseAmount: v.baseAmount!,
        type: v.type as 'INCOME' | 'EXPENSE',
        rrule: `FREQ=MONTHLY;BYMONTHDAY=${v.dayOfMonth}`,
        accountId: v.accountId!,
        categoryId: v.categoryId ?? undefined,
        startDate: v.startDate
          ? new Date(v.startDate).toISOString().split('T')[0]
          : this.defaultStartDate(),
      };
      this.dialogRef.close(create);
    }
  }

  onCancel(): void { this.dialogRef.close(); }

  private dayFromRrule(rrule?: string | null): number {
    if (!rrule) return 1;
    const match = rrule.match(/BYMONTHDAY=(\d+)/);
    return match ? parseInt(match[1], 10) : 1;
  }

  private defaultStartDate(): string {
    const next = new Date();
    next.setMonth(next.getMonth() + 1, 1);
    return next.toISOString().split('T')[0];
  }
}
```

- [ ] **Step 5: Atualizar recurring-item-form.html**

Substitua o template para incluir os novos campos (Conta, Categoria, Data de início) e remover `dayOfMonth` do request:

```html
<h2 mat-dialog-title>{{ existing ? 'Editar Recorrência' : 'Nova Recorrência' }}</h2>

<mat-dialog-content>
  <form [formGroup]="form" class="form-grid">
    <mat-form-field appearance="outline">
      <mat-label>Descrição</mat-label>
      <input matInput formControlName="description" required />
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Valor</mat-label>
      <input matInput type="number" formControlName="baseAmount" min="0.01" required />
    </mat-form-field>

    @if (!isEditMode) {
      <mat-form-field appearance="outline">
        <mat-label>Tipo</mat-label>
        <mat-select formControlName="type" required>
          <mat-option value="INCOME">Receita</mat-option>
          <mat-option value="EXPENSE">Despesa</mat-option>
        </mat-select>
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label>Dia do mês (1–28)</mat-label>
        <input matInput type="number" formControlName="dayOfMonth" min="1" max="28" required />
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label>Conta</mat-label>
        <mat-select formControlName="accountId" required>
          @for (acc of accounts(); track acc.id) {
            <mat-option [value]="acc.id">{{ acc.name }}</mat-option>
          }
        </mat-select>
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label>Categoria (opcional)</mat-label>
        <mat-select formControlName="categoryId">
          <mat-option [value]="null">Sem categoria</mat-option>
          @for (cat of categories(); track cat.id) {
            <mat-option [value]="cat.id">{{ cat.name }}</mat-option>
          }
        </mat-select>
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label>Data de início</mat-label>
        <input matInput [matDatepicker]="picker" formControlName="startDate" required />
        <mat-datepicker-toggle matIconSuffix [for]="picker" />
        <mat-datepicker #picker />
      </mat-form-field>
    }
  </form>
</mat-dialog-content>

<mat-dialog-actions align="end">
  <button mat-button (click)="onCancel()">Cancelar</button>
  <button mat-flat-button color="primary" (click)="onSubmit()" [disabled]="form.invalid">
    {{ existing ? 'Salvar' : 'Criar' }}
  </button>
</mat-dialog-actions>
```

- [ ] **Step 6: Verificar que o frontend compila**

```bash
cd frontend && npx ng build --configuration=development 2>&1 | tail -20
```

Esperado: `Build at:` sem erros. Se houver erros de tipo (ex: `RecurrenceRuleResponseDTO` sem campo `accountId`), verifique o schema gerado pelo Orval em `fintechSaaSAPI.schemas.ts` e ajuste os campos usados.

- [ ] **Step 7: Rodar testes do frontend**

```bash
cd frontend && npm test -- --run 2>&1 | tail -20
```

Esperado: todos os testes passam. Se houver falhas em testes que referenciam `RecurringBudgetItemResponse`, atualize as fixtures de teste.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/features/planning/
git commit -m "feat(recorrencia): rewire aba Recorrentes do planejamento para RecurrenceRule"
```

---

### Task 7: Seed dev + HTTP collection + suite final

**Files:**
- Modify: `docs/http/seed-dataset.http`

**Interfaces:**
- Produces: requests de `/api/recurring-budget-items` substituídos por `/api/recurrence-rules`; suite completa verde.

- [ ] **Step 1: Atualizar docs/http/seed-dataset.http**

Localize as chamadas que referenciam `/api/recurring-budget-items` e substitua pelos equivalentes:

```http
### Listar recorrências
GET {{baseUrl}}/api/recurrence-rules
Authorization: Bearer {{token}}

### Criar recorrência (exemplo: Netflix mensal dia 15)
POST {{baseUrl}}/api/recurrence-rules
Authorization: Bearer {{token}}
Content-Type: application/json

{
  "description": "Netflix",
  "baseAmount": 55.90,
  "type": "EXPENSE",
  "rrule": "FREQ=MONTHLY;BYMONTHDAY=15",
  "accountId": "{{checkingAccountId}}",
  "startDate": "2026-01-15"
}

### Reativar recorrência cancelada
PATCH {{baseUrl}}/api/recurrence-rules/{{ruleId}}/reactivate
Authorization: Bearer {{token}}
```

- [ ] **Step 2: Rodar a suite completa do backend**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -20
```

Esperado: `BUILD SUCCESS` com 0 falhas.

- [ ] **Step 3: Rodar a suite completa do frontend**

```bash
cd frontend && npm test -- --run 2>&1 | tail -20
```

Esperado: todos os testes passam.

- [ ] **Step 4: Commit final**

```bash
git add docs/http/seed-dataset.http
git commit -m "docs(recorrencia): atualiza seed-dataset.http — substitui recurring-budget-items por recurrence-rules"
```

---

## Checklist de verificação final

Antes de sugerir merge em `develop`, confirme:

- [ ] `GET /api/recurring-budget-items` retorna 404 (endpoint removido)
- [ ] `GET /api/recurrence-rules` retorna 200 com regras ACTIVE
- [ ] `PATCH /api/recurrence-rules/{id}/reactivate` retorna 200 para regra CANCELLED
- [ ] Abrir ciclo com regra ACTIVE → `BudgetItem` tem `recurrenceRuleId` preenchido
- [ ] Abrir ciclo com regra CANCELLED → nenhum `BudgetItem` RECURRING criado
- [ ] Aba "Recorrentes" do planejamento lista regras, abre form com campos novos (Conta, Dia, Data de início), cria/edita/cancela/reativa sem erros
- [ ] `./mvnw test -q` → BUILD SUCCESS
- [ ] `npm test -- --run` → todos os testes passam
