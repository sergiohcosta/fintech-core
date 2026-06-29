# Motor de Recorrência — Núcleo: Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Entregar o núcleo do Motor de Recorrência — regras (RRULE), projeção on-the-fly das "linhas fantasma" na lista de transações, e as ações Confirmar/Pular — sem materializar nada antes da confirmação do usuário.

**Architecture:** Tabela `recurrence_rules` (fonte única, tenant-scoped) guarda a regra como string RFC 5545 (RRULE). `RecurrenceProjectionService` expande as regras ativas numa janela via `lib-recur`, subtrai ocorrências já materializadas (`transactions` com `recurrence_rule_id`) e exceções (`recurrence_exceptions` = EXDATE). `GET /api/transactions?includeProjected=true` mescla reais + fantasmas. Confirmar reusa o caminho de criação de transação existente (fatura de cartão resolvida de graça); Pular grava uma EXDATE.

**Tech Stack:** Java 21 · Spring Boot 4.0.1 · Spring Data JPA · Flyway · `org.dmfs:lib-recur` (expansão RRULE no backend) · Angular 21 Zoneless · Orval · `rrule` (npm, editor/preview no frontend) · Testcontainers · Vitest.

**Spec de referência:** `docs/superpowers/specs/2026-06-25-motor-de-recorrencia-nucleo-design.md`

## Global Constraints

- **Multi-tenant:** toda query de negócio filtra pelo `Tenant` do usuário autenticado. Vazamento de tenant é o bug mais grave do projeto. Entidades novas têm `tenant_id NOT NULL`.
- **Schema só via Flyway:** migrations imutáveis; nunca `ddl-auto=update`. Próximas versões livres: **V19** (schema), **V20** (seed dev).
- **Entidade JPA nunca exposta** em controller — sempre DTO. DTOs com Bean Validation.
- **Spec-first:** editar `api-spec/openapi.yaml` primeiro → `./mvnw generate-sources` gera as interfaces `*Api` que os controllers implementam → `npm run api:generate` no frontend → copiar a spec para `backend/src/main/resources/static/openapi.yaml`.
- **Exceções:** services nunca lançam `jakarta.persistence.EntityNotFoundException`; usar `com.fintech.api.exception.EntityNotFoundException` (404) e `BusinessConflictException` (409). Validação `@Valid` → 400 automático via `GlobalExceptionHandler`.
- **Auth:** controllers obtêm o usuário via `SecurityUtils.currentUser()`. `/api/recurrence-rules/**` cai em `anyRequest().authenticated()` — **sem** restrição de role (coerente com budget items, acessíveis a MEMBER). Nenhuma alteração em `SecurityConfigurations.java`.
- **RRULE subset aceito:** `FREQ=MONTHLY|YEARLY`, `INTERVAL`, `BYMONTHDAY` (1..31 e `-1`), `UNTIL`, `COUNT`. Rejeitar o resto.
- **Dataset vivo:** mudança de banco obriga atualizar seed (`V20`), `seed_base.sql` e `seed-dataset.http`.
- **PT-BR** em comentários/commits; identificadores em inglês. Commits no imperativo, **sem** `Co-Authored-By`.
- **Frontend Zoneless, signals-first, TS estrito (sem `any`), SCSS + Material 3.**

---

## File Structure

**Backend (cria):**
- `db/migration/V19__recurrence_rules.sql` — schema.
- `db/seed/V20__seed_dev_recurrence.sql` — seed dev.
- `domain/recurrence/RecurrenceRule.java`, `domain/recurrence/RecurrenceException.java`
- `domain/enums/RecurrenceStatus.java`
- `repository/RecurrenceRuleRepository.java`, `repository/RecurrenceExceptionRepository.java`
- `service/recurrence/RecurrenceExpander.java` — wrapper do `lib-recur` (expansão).
- `service/recurrence/RecurrenceProjectionService.java`, `service/RecurrenceRuleService.java`
- `service/recurrence/ProjectedOccurrence.java` — record da projeção.
- `dto/recurrence/{RecurrenceRuleCreateDTO,RecurrenceRuleResponseDTO,RecurrenceRulePatchDTO,ConfirmOccurrenceDTO}.java`
- `dto/recurrence/ValidRrule.java` + `dto/recurrence/RruleValidator.java` — Bean Validation custom.
- `controller/RecurrenceRulesController.java`

**Backend (modifica):**
- `pom.xml` — dependência `lib-recur`.
- `domain/transaction/Transaction.java` — `recurrenceRule`, `recurrenceOccurrence`.
- `repository/TransactionRepository.java` — query de ocorrências materializadas.
- `service/TransactionService.java` — merge de projeção em `findAll`; `materializeFromRule`.
- `controller/TransactionController.java` — parâmetro `includeProjected`.
- `dto/transaction/TransactionResponseDTO.java` — campos `projected`, `recurrenceRuleId`, `occurrenceDate` + `fromProjection`.
- `src/test/resources/sql/seed_base.sql` — 1 regra mínima.
- `api-spec/openapi.yaml` — paths e schemas novos.

**Frontend (cria):**
- `features/recurrence/rrule-editor/rrule-builder.ts` (+ `.spec.ts`) — lógica pura form→RRULE.
- `features/recurrence/rrule-editor/rrule-editor.ts/.html/.scss`
- `features/recurrence/recurrence-list/recurrence-list.ts/.html/.scss`
- `features/recurrence/recurrence.routes.ts`
- `core/services/recurrence.service.ts`

**Frontend (modifica):**
- `package.json` — `rrule`.
- `features/transaction/transaction-form/transaction-form.*` — toggle "repetir".
- `features/transaction/transaction-list/transaction-list.*` + `transaction-list.utils.ts` — fantasma + Confirmar/Pular.
- rotas raiz — registrar `recurrences/` lazy.

---

## Task 1: Schema, entidades e repositórios de recorrência

**Files:**
- Create: `backend/src/main/resources/db/migration/V19__recurrence_rules.sql`
- Create: `backend/src/main/java/com/fintech/api/domain/recurrence/RecurrenceRule.java`
- Create: `backend/src/main/java/com/fintech/api/domain/recurrence/RecurrenceException.java`
- Create: `backend/src/main/java/com/fintech/api/domain/enums/RecurrenceStatus.java`
- Create: `backend/src/main/java/com/fintech/api/repository/RecurrenceRuleRepository.java`
- Create: `backend/src/main/java/com/fintech/api/repository/RecurrenceExceptionRepository.java`
- Modify: `backend/src/main/java/com/fintech/api/domain/transaction/Transaction.java`
- Modify: `backend/src/main/java/com/fintech/api/repository/TransactionRepository.java`
- Test: `backend/src/test/java/com/fintech/api/repository/RecurrenceRuleRepositoryTest.java`

**Interfaces:**
- Produces:
  - `RecurrenceStatus { ACTIVE, CANCELLED }`
  - `RecurrenceRule` (campos: `UUID id`, `Tenant tenant`, `String description`, `BigDecimal baseAmount`, `TransactionType type`, `Category category`, `Account account`, `String rrule`, `LocalDate startDate`, `RecurrenceStatus status`, `User createdBy`, timestamps) com `@Builder`.
  - `RecurrenceException` (`UUID id`, `RecurrenceRule rule`, `LocalDate occurrenceDate`) com `@Builder`.
  - `RecurrenceRuleRepository.findByTenantAndStatus(Tenant, RecurrenceStatus) : List<RecurrenceRule>`; `findByIdAndTenant(UUID, Tenant) : Optional<RecurrenceRule>`.
  - `RecurrenceExceptionRepository.findByRuleIdInAndOccurrenceDateBetween(Collection<UUID>, LocalDate, LocalDate) : List<RecurrenceException>`; `existsByRuleIdAndOccurrenceDate(UUID, LocalDate) : boolean`.
  - `Transaction.getRecurrenceRule()/getRecurrenceOccurrence()` + builder.
  - `TransactionRepository.findByRecurrenceRuleIdInAndRecurrenceOccurrenceBetween(Collection<UUID>, LocalDate, LocalDate) : List<Transaction>`.

- [ ] **Step 1: Escrever a migration V19**

Create `backend/src/main/resources/db/migration/V19__recurrence_rules.sql`:

```sql
-- Motor de Recorrência (núcleo). A "Regra" é atemporal; a recorrência temporal
-- vive na string RRULE (RFC 5545), expandida on-the-fly. Nada é materializado aqui.
CREATE TABLE recurrence_rules (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL REFERENCES tenants(id),
    description  VARCHAR(255) NOT NULL,
    base_amount  NUMERIC      NOT NULL,
    type         VARCHAR(10)  NOT NULL,                 -- INCOME | EXPENSE
    category_id  UUID         REFERENCES categories(id),
    account_id   UUID         NOT NULL REFERENCES accounts(id),
    rrule        TEXT         NOT NULL,                 -- ex: FREQ=MONTHLY;BYMONTHDAY=15
    start_date   DATE         NOT NULL,                 -- DTSTART (âncora da expansão)
    status       VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',-- ACTIVE | CANCELLED
    created_by   UUID         REFERENCES users(id),
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT chk_recurrence_type   CHECK (type   IN ('INCOME','EXPENSE')),
    CONSTRAINT chk_recurrence_status CHECK (status IN ('ACTIVE','CANCELLED'))
);
CREATE INDEX idx_recurrence_rules_tenant_status ON recurrence_rules(tenant_id, status);

-- EXDATE: só ganha linha quando o usuário PULA de fato (tabela esparsa).
CREATE TABLE recurrence_exceptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id         UUID NOT NULL REFERENCES recurrence_rules(id) ON DELETE CASCADE,
    occurrence_date DATE NOT NULL,
    UNIQUE (rule_id, occurrence_date)
);
CREATE INDEX idx_recurrence_exceptions_rule_date ON recurrence_exceptions(rule_id, occurrence_date);

-- Liga a transação materializada à ocorrência da regra que ela satisfaz.
-- recurrence_occurrence é o "slot" canônico da regra (≠ date, que é a data efetiva).
ALTER TABLE transactions
    ADD COLUMN recurrence_rule_id    UUID REFERENCES recurrence_rules(id),
    ADD COLUMN recurrence_occurrence DATE;
-- Impede confirmar a mesma ocorrência duas vezes (índice único parcial).
CREATE UNIQUE INDEX uq_transactions_rule_occurrence
    ON transactions(recurrence_rule_id, recurrence_occurrence)
    WHERE recurrence_rule_id IS NOT NULL;
```

- [ ] **Step 2: Criar o enum e as entidades**

Create `backend/src/main/java/com/fintech/api/domain/enums/RecurrenceStatus.java`:

```java
package com.fintech.api.domain.enums;

public enum RecurrenceStatus {
    ACTIVE, CANCELLED
}
```

Create `backend/src/main/java/com/fintech/api/domain/recurrence/RecurrenceRule.java`:

```java
package com.fintech.api.domain.recurrence;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.RecurrenceStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "recurrence_rules")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RecurrenceRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    @ToString.Exclude
    private Tenant tenant;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(name = "base_amount", nullable = false)
    private BigDecimal baseAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    @ToString.Exclude
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    @ToString.Exclude
    private Account account;

    @Column(nullable = false, columnDefinition = "text")
    private String rrule;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private RecurrenceStatus status = RecurrenceStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    @ToString.Exclude
    private User createdBy;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

Create `backend/src/main/java/com/fintech/api/domain/recurrence/RecurrenceException.java`:

```java
package com.fintech.api.domain.recurrence;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "recurrence_exceptions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RecurrenceException {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    @ToString.Exclude
    private RecurrenceRule rule;

    @Column(name = "occurrence_date", nullable = false)
    private LocalDate occurrenceDate;
}
```

- [ ] **Step 3: Adicionar as colunas de recorrência em `Transaction`**

In `backend/src/main/java/com/fintech/api/domain/transaction/Transaction.java`, after the `invoice` field (line ~93), add:

```java
    // --- RECORRÊNCIA ---
    // Preenchido quando a transação foi materializada a partir de uma regra.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recurrence_rule_id")
    @ToString.Exclude
    private com.fintech.api.domain.recurrence.RecurrenceRule recurrenceRule;

    // O "slot" canônico da regra (data da ocorrência), distinto de `date` (data efetiva).
    @Column(name = "recurrence_occurrence")
    private LocalDate recurrenceOccurrence;
```

- [ ] **Step 4: Criar os repositórios**

Create `backend/src/main/java/com/fintech/api/repository/RecurrenceRuleRepository.java`:

```java
package com.fintech.api.repository;

import com.fintech.api.domain.enums.RecurrenceStatus;
import com.fintech.api.domain.recurrence.RecurrenceRule;
import com.fintech.api.domain.tenant.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecurrenceRuleRepository extends JpaRepository<RecurrenceRule, UUID> {
    List<RecurrenceRule> findByTenantAndStatus(Tenant tenant, RecurrenceStatus status);
    Optional<RecurrenceRule> findByIdAndTenant(UUID id, Tenant tenant);
}
```

Create `backend/src/main/java/com/fintech/api/repository/RecurrenceExceptionRepository.java`:

```java
package com.fintech.api.repository;

import com.fintech.api.domain.recurrence.RecurrenceException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RecurrenceExceptionRepository extends JpaRepository<RecurrenceException, UUID> {
    // Batched — evita N+1 ao projetar várias regras na mesma janela.
    List<RecurrenceException> findByRuleIdInAndOccurrenceDateBetween(
            Collection<UUID> ruleIds, LocalDate from, LocalDate to);

    boolean existsByRuleIdAndOccurrenceDate(UUID ruleId, LocalDate occurrenceDate);
}
```

In `backend/src/main/java/com/fintech/api/repository/TransactionRepository.java`, add the method (mantenha os imports existentes; `Collection`/`LocalDate` podem precisar de import):

```java
    // Ocorrências já materializadas das regras informadas, na janela. Usado pela projeção
    // para subtrair da expansão RRULE o que já virou transação real.
    java.util.List<com.fintech.api.domain.transaction.Transaction>
        findByRecurrenceRuleIdInAndRecurrenceOccurrenceBetween(
            java.util.Collection<java.util.UUID> ruleIds,
            java.time.LocalDate from, java.time.LocalDate to);
```

- [ ] **Step 5: Escrever o teste de repositório (deve falhar)**

Create `backend/src/test/java/com/fintech/api/repository/RecurrenceRuleRepositoryTest.java`. Siga o padrão de teste de integração já existente no projeto (Testcontainers + perfil de teste). Estrutura:

```java
package com.fintech.api.repository;

import com.fintech.api.domain.enums.RecurrenceStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.recurrence.RecurrenceRule;
import com.fintech.api.domain.tenant.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RecurrenceRuleRepositoryTest {

    @Autowired RecurrenceRuleRepository ruleRepository;
    @Autowired com.fintech.api.repository.TenantRepository tenantRepository;

    @Test
    void persisteEConsultaRegraAtivaPorTenant() {
        Tenant tenant = tenantRepository.findAll().getFirst(); // seed_base garante 1 tenant

        RecurrenceRule rule = ruleRepository.save(RecurrenceRule.builder()
                .tenant(tenant)
                .description("Netflix")
                .baseAmount(new BigDecimal("50.00"))
                .type(TransactionType.EXPENSE)
                .rrule("FREQ=MONTHLY;BYMONTHDAY=15")
                .startDate(LocalDate.of(2026, 1, 15))
                .status(RecurrenceStatus.ACTIVE)
                .build());

        List<RecurrenceRule> active = ruleRepository.findByTenantAndStatus(tenant, RecurrenceStatus.ACTIVE);
        assertThat(active).extracting(RecurrenceRule::getId).contains(rule.getId());
    }
}
```

> **Nota ao implementador:** confira como os outros `*RepositoryTest`/`*IT` do projeto sobem o Testcontainers (classe base comum ou anotação). Reuse o mesmo mecanismo em vez de reconfigurar do zero. `seed_base.sql` precisa rodar antes (Task 7 adiciona a regra mínima lá; aqui só dependemos de existir 1 tenant, que o seed_base já garante).

- [ ] **Step 6: Rodar o teste e ver falhar**

Run: `cd backend && ./mvnw test -Dtest=RecurrenceRuleRepositoryTest`
Expected: FALHA na compilação/contexto (tabela/entidade ainda não existiam antes desta task) ou, se compilar, asserção verde. O objetivo é confirmar que o schema sobe limpo.

- [ ] **Step 7: Rodar o teste e ver passar**

Run: `cd backend && ./mvnw test -Dtest=RecurrenceRuleRepositoryTest`
Expected: PASS. Flyway aplica V19 no Testcontainer; a regra é persistida e consultada.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V19__recurrence_rules.sql \
        backend/src/main/java/com/fintech/api/domain/recurrence/ \
        backend/src/main/java/com/fintech/api/domain/enums/RecurrenceStatus.java \
        backend/src/main/java/com/fintech/api/domain/transaction/Transaction.java \
        backend/src/main/java/com/fintech/api/repository/RecurrenceRuleRepository.java \
        backend/src/main/java/com/fintech/api/repository/RecurrenceExceptionRepository.java \
        backend/src/main/java/com/fintech/api/repository/TransactionRepository.java \
        backend/src/test/java/com/fintech/api/repository/RecurrenceRuleRepositoryTest.java
git commit -m "feat(recorrencia): adiciona schema, entidades e repositórios de recorrência"
```

---

## Task 2: Expansão RRULE (`lib-recur`) e validador do subconjunto

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/fintech/api/service/recurrence/RecurrenceExpander.java`
- Create: `backend/src/main/java/com/fintech/api/dto/recurrence/ValidRrule.java`
- Create: `backend/src/main/java/com/fintech/api/dto/recurrence/RruleValidator.java`
- Test: `backend/src/test/java/com/fintech/api/service/recurrence/RecurrenceExpanderTest.java`

**Interfaces:**
- Consumes: nada de tasks anteriores.
- Produces:
  - `RecurrenceExpander.expand(String rrule, LocalDate startDate, LocalDate from, LocalDate to) : List<LocalDate>` — datas de ocorrência dentro de `[from, to]` (inclusive), respeitando `UNTIL`/`COUNT` da regra.
  - `RecurrenceExpander.isSupported(String rrule) : boolean` — true se o rrule está no subconjunto financeiro.
  - `@ValidRrule` (anotação Bean Validation) + `RruleValidator`.

- [ ] **Step 1: Adicionar a dependência `lib-recur` ao pom**

In `backend/pom.xml`, dentro de `<dependencies>`, adicione:

```xml
		<!-- Expansão de regras de recorrência RFC 5545 (RRULE). Evita reescrever
		     matemática de datas (ano bissexto, último dia do mês, UNTIL/COUNT). -->
		<dependency>
			<groupId>org.dmfs</groupId>
			<artifactId>lib-recur</artifactId>
			<version>0.16.0</version>
		</dependency>
```

> **Nota:** confirme a versão estável mais recente no Maven Central (`org.dmfs:lib-recur`) e ajuste se necessário. Rode `./mvnw dependency:resolve` para baixar.

- [ ] **Step 2: Escrever o teste do expander (deve falhar)**

Create `backend/src/test/java/com/fintech/api/service/recurrence/RecurrenceExpanderTest.java`:

```java
package com.fintech.api.service.recurrence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecurrenceExpanderTest {

    private final RecurrenceExpander expander = new RecurrenceExpander();

    @Test
    void expandeMensalNoDiaFixoDentroDaJanela() {
        List<LocalDate> dates = expander.expand(
                "FREQ=MONTHLY;BYMONTHDAY=15", LocalDate.of(2026, 1, 15),
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 31));
        assertThat(dates).containsExactly(
                LocalDate.of(2026, 3, 15), LocalDate.of(2026, 4, 15), LocalDate.of(2026, 5, 15));
    }

    // Teste A (reframado): "fim do mês" = BYMONTHDAY=-1 ancora no último dia válido,
    // resolvendo nativamente fev (28/29), abr (30), mar (31).
    @Test
    void ultimoDiaDoMesAncoraNoDiaValido() {
        List<LocalDate> dates = expander.expand(
                "FREQ=MONTHLY;BYMONTHDAY=-1", LocalDate.of(2024, 1, 31),
                LocalDate.of(2024, 2, 1), LocalDate.of(2024, 4, 30));
        assertThat(dates).containsExactly(
                LocalDate.of(2024, 2, 29),  // 2024 é bissexto
                LocalDate.of(2024, 3, 31),
                LocalDate.of(2024, 4, 30));
    }

    @Test
    void respeitaCountComoLimiteRigido() {
        List<LocalDate> dates = expander.expand(
                "FREQ=MONTHLY;BYMONTHDAY=10;COUNT=2", LocalDate.of(2026, 1, 10),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(dates).containsExactly(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 2, 10));
    }

    @Test
    void aceitaSubconjuntoERejeitaForaDele() {
        assertThat(expander.isSupported("FREQ=MONTHLY;INTERVAL=3;BYMONTHDAY=5")).isTrue();
        assertThat(expander.isSupported("FREQ=YEARLY;BYMONTHDAY=1")).isTrue();
        assertThat(expander.isSupported("FREQ=WEEKLY;BYDAY=MO")).isFalse();
        assertThat(expander.isSupported("FREQ=MONTHLY;BYSETPOS=-1")).isFalse();
        assertThat(expander.isSupported("lixo")).isFalse();
    }
}
```

- [ ] **Step 3: Rodar e ver falhar**

Run: `cd backend && ./mvnw test -Dtest=RecurrenceExpanderTest`
Expected: FALHA de compilação ("cannot find symbol RecurrenceExpander").

- [ ] **Step 4: Implementar o `RecurrenceExpander`**

Create `backend/src/main/java/com/fintech/api/service/recurrence/RecurrenceExpander.java`:

```java
package com.fintech.api.service.recurrence;

import org.dmfs.rfc5545.DateTime;
import org.dmfs.rfc5545.recur.InvalidRecurrenceRuleException;
import org.dmfs.rfc5545.recur.RecurrenceRule;
import org.dmfs.rfc5545.recur.RecurrenceRuleIterator;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Wrapper fino sobre lib-recur (RFC 5545). Concentra duas responsabilidades:
 * expandir um RRULE numa janela de datas e validar que o RRULE está no
 * subconjunto financeiro suportado.
 *
 * <p><b>Gotcha pedagógico:</b> o {@link DateTime} do lib-recur usa <b>mês 0-based</b>
 * (janeiro = 0), diferente de {@link LocalDate}. As conversões abaixo compensam isso.
 */
@Component
public class RecurrenceExpander {

    // FREQ permitidos. O resto do RRULE (weekly/daily, BYDAY, BYSETPOS...) é de app de
    // agenda, não de finanças, e quebraria a lógica de orçamento mensal.
    private static final Set<RecurrenceRule.Freq> ALLOWED_FREQ =
            Set.of(RecurrenceRule.Freq.MONTHLY, RecurrenceRule.Freq.YEARLY);

    // Partes proibidas: se qualquer uma aparecer, o rrule está fora do subconjunto.
    private static final Set<RecurrenceRule.Part> FORBIDDEN_PARTS = Set.of(
            RecurrenceRule.Part.BYDAY, RecurrenceRule.Part.BYSETPOS,
            RecurrenceRule.Part.BYWEEKNO, RecurrenceRule.Part.BYYEARDAY,
            RecurrenceRule.Part.BYHOUR, RecurrenceRule.Part.BYMINUTE);

    public List<LocalDate> expand(String rrule, LocalDate startDate, LocalDate from, LocalDate to) {
        List<LocalDate> result = new ArrayList<>();
        try {
            RecurrenceRule rule = new RecurrenceRule(rrule);
            DateTime start = toDateTime(startDate);
            RecurrenceRuleIterator it = rule.iterator(start);

            // Pula direto para o início da janela (eficiência: não itera o histórico).
            it.fastForward(toDateTime(from));

            DateTime upperBound = toDateTime(to.plusDays(1)); // 'to' inclusivo
            while (it.hasNext()) {
                DateTime next = it.nextDateTime();
                if (!next.before(upperBound)) break; // saiu da janela (ou COUNT/UNTIL acabou antes)
                result.add(toLocalDate(next));
            }
        } catch (InvalidRecurrenceRuleException e) {
            // rrule inválido nunca deveria chegar aqui (validado na entrada), mas a
            // projeção não pode derrubar a lista inteira por uma regra ruim: ignora.
            return List.of();
        }
        return result;
    }

    public boolean isSupported(String rrule) {
        try {
            RecurrenceRule rule = new RecurrenceRule(rrule);
            if (!ALLOWED_FREQ.contains(rule.getFreq())) return false;
            for (RecurrenceRule.Part part : FORBIDDEN_PARTS) {
                if (rule.hasPart(part)) return false;
            }
            return true;
        } catch (InvalidRecurrenceRuleException e) {
            return false;
        }
    }

    private DateTime toDateTime(LocalDate d) {
        // mês 0-based no lib-recur; data "floating" (sem timezone) basta para datas de calendário.
        return new DateTime(d.getYear(), d.getMonthValue() - 1, d.getDayOfMonth());
    }

    private LocalDate toLocalDate(DateTime dt) {
        return LocalDate.of(dt.getYear(), dt.getMonth() + 1, dt.getDayOfMonth());
    }
}
```

> **Nota:** os nomes exatos da API do lib-recur (`getFreq()`, `hasPart(Part)`, `Part`, `Freq`, `fastForward`, `nextDateTime`) podem variar levemente entre versões. Ajuste ao Javadoc da versão resolvida; a forma (expandir com iterador + janela, mês 0-based) está correta.

- [ ] **Step 5: Rodar e ver passar**

Run: `cd backend && ./mvnw test -Dtest=RecurrenceExpanderTest`
Expected: PASS (4 testes).

- [ ] **Step 6: Criar a anotação `@ValidRrule` e o validador**

Create `backend/src/main/java/com/fintech/api/dto/recurrence/ValidRrule.java`:

```java
package com.fintech.api.dto.recurrence;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = RruleValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidRrule {
    String message() default "RRULE fora do subconjunto suportado (FREQ MONTHLY/YEARLY, INTERVAL, BYMONTHDAY, UNTIL, COUNT)";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

Create `backend/src/main/java/com/fintech/api/dto/recurrence/RruleValidator.java`:

```java
package com.fintech.api.dto.recurrence;

import com.fintech.api.service.recurrence.RecurrenceExpander;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class RruleValidator implements ConstraintValidator<ValidRrule, String> {

    private final RecurrenceExpander expander;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) return false;
        return expander.isSupported(value);
    }
}
```

> **Nota:** o `RecurrenceExpander` é injetado no validador — Spring suporta validadores como beans gerenciados (Hibernate Validator usa o `SpringConstraintValidatorFactory`). Sem necessidade de configuração extra.

- [ ] **Step 7: Commit**

```bash
git add backend/pom.xml \
        backend/src/main/java/com/fintech/api/service/recurrence/RecurrenceExpander.java \
        backend/src/main/java/com/fintech/api/dto/recurrence/ValidRrule.java \
        backend/src/main/java/com/fintech/api/dto/recurrence/RruleValidator.java \
        backend/src/test/java/com/fintech/api/service/recurrence/RecurrenceExpanderTest.java
git commit -m "feat(recorrencia): adiciona expansão RRULE via lib-recur e validador do subconjunto"
```

---

## Task 3: Serviço de projeção (fantasma = expansão − materializadas − EXDATE)

**Files:**
- Create: `backend/src/main/java/com/fintech/api/service/recurrence/ProjectedOccurrence.java`
- Create: `backend/src/main/java/com/fintech/api/service/recurrence/RecurrenceProjectionService.java`
- Test: `backend/src/test/java/com/fintech/api/service/recurrence/RecurrenceProjectionServiceTest.java`

**Interfaces:**
- Consumes: `RecurrenceRuleRepository`, `RecurrenceExceptionRepository`, `TransactionRepository.findByRecurrenceRuleIdInAndRecurrenceOccurrenceBetween` (Task 1); `RecurrenceExpander.expand` (Task 2).
- Produces:
  - `ProjectedOccurrence` (record: `UUID ruleId`, `LocalDate occurrenceDate`, `String description`, `BigDecimal amount`, `TransactionType type`, `UUID categoryId`, `String categoryName`, `String categoryIcon`, `UUID accountId`, `String accountName`).
  - `RecurrenceProjectionService.project(Tenant tenant, LocalDate from, LocalDate to) : List<ProjectedOccurrence>`.

- [ ] **Step 1: Criar o record `ProjectedOccurrence`**

Create `backend/src/main/java/com/fintech/api/service/recurrence/ProjectedOccurrence.java`:

```java
package com.fintech.api.service.recurrence;

import com.fintech.api.domain.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Uma "linha fantasma" projetada — nunca persistida. */
public record ProjectedOccurrence(
        UUID ruleId,
        LocalDate occurrenceDate,
        String description,
        BigDecimal amount,
        TransactionType type,
        UUID categoryId,
        String categoryName,
        String categoryIcon,
        UUID accountId,
        String accountName) {
}
```

- [ ] **Step 2: Escrever o teste de projeção (deve falhar)**

Create `backend/src/test/java/com/fintech/api/service/recurrence/RecurrenceProjectionServiceTest.java`. Usa Mockito (sem banco) — testa a lógica de subtração:

```java
package com.fintech.api.service.recurrence;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.RecurrenceStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.recurrence.RecurrenceException;
import com.fintech.api.domain.recurrence.RecurrenceRule;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.repository.RecurrenceExceptionRepository;
import com.fintech.api.repository.RecurrenceRuleRepository;
import com.fintech.api.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecurrenceProjectionServiceTest {

    @Mock RecurrenceRuleRepository ruleRepository;
    @Mock RecurrenceExceptionRepository exceptionRepository;
    @Mock TransactionRepository transactionRepository;
    @InjectMocks RecurrenceProjectionService service;
    // RecurrenceExpander é real (lógica pura); injetar manualmente no setup se @InjectMocks não cobrir.

    private RecurrenceRule netflix(Tenant tenant, Account acc) {
        return RecurrenceRule.builder()
                .id(UUID.randomUUID()).tenant(tenant).account(acc)
                .description("Netflix").baseAmount(new BigDecimal("100.00"))
                .type(TransactionType.EXPENSE)
                .rrule("FREQ=MONTHLY;BYMONTHDAY=10")
                .startDate(LocalDate.of(2026, 1, 10))
                .status(RecurrenceStatus.ACTIVE)
                .build();
    }

    // Teste B (inflação): confirmar agosto a 150 (materializado) não some a fantasma de
    // setembro, que volta ao base 100.
    @Test
    void subtraiOcorrenciaMaterializadaMasMantemAsDemais() {
        Tenant tenant = new Tenant();
        Account acc = new Account(); acc.setName("Cartão");
        RecurrenceRule rule = netflix(tenant, acc);

        when(ruleRepository.findByTenantAndStatus(tenant, RecurrenceStatus.ACTIVE)).thenReturn(List.of(rule));
        Transaction ago = Transaction.builder().recurrenceRule(rule)
                .recurrenceOccurrence(LocalDate.of(2026, 8, 10)).build();
        when(transactionRepository.findByRecurrenceRuleIdInAndRecurrenceOccurrenceBetween(any(), any(), any()))
                .thenReturn(List.of(ago));
        when(exceptionRepository.findByRuleIdInAndOccurrenceDateBetween(any(), any(), any()))
                .thenReturn(List.of());

        List<ProjectedOccurrence> ghosts = service.project(tenant,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30));

        assertThat(ghosts).extracting(ProjectedOccurrence::occurrenceDate)
                .containsExactly(LocalDate.of(2026, 9, 10)); // agosto sumiu; setembro fantasma
        assertThat(ghosts.getFirst().amount()).isEqualByComparingTo("100.00"); // volta ao base
    }

    // Teste C (pular): EXDATE no mês vigente remove só aquela ocorrência.
    @Test
    void subtraiExdate() {
        Tenant tenant = new Tenant();
        Account acc = new Account(); acc.setName("Cartão");
        RecurrenceRule rule = netflix(tenant, acc);

        when(ruleRepository.findByTenantAndStatus(tenant, RecurrenceStatus.ACTIVE)).thenReturn(List.of(rule));
        when(transactionRepository.findByRecurrenceRuleIdInAndRecurrenceOccurrenceBetween(any(), any(), any()))
                .thenReturn(List.of());
        RecurrenceException skip = RecurrenceException.builder()
                .rule(rule).occurrenceDate(LocalDate.of(2026, 8, 10)).build();
        when(exceptionRepository.findByRuleIdInAndOccurrenceDateBetween(any(), any(), any()))
                .thenReturn(List.of(skip));

        List<ProjectedOccurrence> ghosts = service.project(tenant,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30));

        assertThat(ghosts).extracting(ProjectedOccurrence::occurrenceDate)
                .containsExactly(LocalDate.of(2026, 9, 10)); // agosto pulado
    }
}
```

> **Nota:** `RecurrenceExpander` é lógica pura (sem mocks). Se `@InjectMocks` não o construir, instancie o serviço no `@BeforeEach` passando `new RecurrenceExpander()` + os mocks.

- [ ] **Step 3: Rodar e ver falhar**

Run: `cd backend && ./mvnw test -Dtest=RecurrenceProjectionServiceTest`
Expected: FALHA de compilação ("cannot find symbol RecurrenceProjectionService").

- [ ] **Step 4: Implementar o `RecurrenceProjectionService`**

Create `backend/src/main/java/com/fintech/api/service/recurrence/RecurrenceProjectionService.java`:

```java
package com.fintech.api.service.recurrence;

import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.RecurrenceStatus;
import com.fintech.api.domain.recurrence.RecurrenceRule;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.repository.RecurrenceExceptionRepository;
import com.fintech.api.repository.RecurrenceRuleRepository;
import com.fintech.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Projeta as "linhas fantasma" de uma janela, on-the-fly. Nada é persistido.
 *
 * <p>fantasma(janela) = expand(rrule) − {ocorrências já materializadas} − {EXDATE}.
 * Tudo keyed pela data da ocorrência. Custo: 3 queries indexadas por tenant + expansão
 * em memória limitada à janela (exceções são esparsas).
 */
@Service
@RequiredArgsConstructor
public class RecurrenceProjectionService {

    private final RecurrenceRuleRepository ruleRepository;
    private final RecurrenceExceptionRepository exceptionRepository;
    private final TransactionRepository transactionRepository;
    private final RecurrenceExpander expander;

    @Transactional(readOnly = true)
    public List<ProjectedOccurrence> project(Tenant tenant, LocalDate from, LocalDate to) {
        List<RecurrenceRule> rules = ruleRepository.findByTenantAndStatus(tenant, RecurrenceStatus.ACTIVE);
        if (rules.isEmpty()) return List.of();

        List<UUID> ruleIds = rules.stream().map(RecurrenceRule::getId).toList();

        // Ocorrências já resolvidas, batched (sem N+1):
        Set<DatedRule> materialized = transactionRepository
                .findByRecurrenceRuleIdInAndRecurrenceOccurrenceBetween(ruleIds, from, to).stream()
                .map(t -> new DatedRule(t.getRecurrenceRule().getId(), t.getRecurrenceOccurrence()))
                .collect(Collectors.toSet());
        Set<DatedRule> skipped = exceptionRepository
                .findByRuleIdInAndOccurrenceDateBetween(ruleIds, from, to).stream()
                .map(e -> new DatedRule(e.getRule().getId(), e.getOccurrenceDate()))
                .collect(Collectors.toSet());

        List<ProjectedOccurrence> ghosts = new ArrayList<>();
        for (RecurrenceRule rule : rules) {
            for (LocalDate date : expander.expand(rule.getRrule(), rule.getStartDate(), from, to)) {
                DatedRule key = new DatedRule(rule.getId(), date);
                if (materialized.contains(key) || skipped.contains(key)) continue;
                ghosts.add(toOccurrence(rule, date));
            }
        }
        return ghosts;
    }

    private ProjectedOccurrence toOccurrence(RecurrenceRule rule, LocalDate date) {
        Category cat = rule.getCategory();
        return new ProjectedOccurrence(
                rule.getId(), date, rule.getDescription(), rule.getBaseAmount(), rule.getType(),
                cat != null ? cat.getId() : null,
                cat != null ? cat.getName() : null,
                cat != null ? cat.getIcon() : null,
                rule.getAccount().getId(), rule.getAccount().getName());
    }

    // Chave (regra, data) para subtração O(1).
    private record DatedRule(UUID ruleId, LocalDate date) {}
}
```

- [ ] **Step 5: Rodar e ver passar**

Run: `cd backend && ./mvnw test -Dtest=RecurrenceProjectionServiceTest`
Expected: PASS (2 testes — B e C).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/recurrence/ProjectedOccurrence.java \
        backend/src/main/java/com/fintech/api/service/recurrence/RecurrenceProjectionService.java \
        backend/src/test/java/com/fintech/api/service/recurrence/RecurrenceProjectionServiceTest.java
git commit -m "feat(recorrencia): adiciona serviço de projeção das linhas fantasma"
```

---

## Task 4: CRUD de regras — DTOs, openapi, service, controller

**Files:**
- Modify: `api-spec/openapi.yaml`
- Create: `backend/src/main/java/com/fintech/api/dto/recurrence/RecurrenceRuleCreateDTO.java`
- Create: `backend/src/main/java/com/fintech/api/dto/recurrence/RecurrenceRuleResponseDTO.java`
- Create: `backend/src/main/java/com/fintech/api/dto/recurrence/RecurrenceRulePatchDTO.java`
- Create: `backend/src/main/java/com/fintech/api/service/RecurrenceRuleService.java`
- Create: `backend/src/main/java/com/fintech/api/controller/RecurrenceRulesController.java`
- Test: `backend/src/test/java/com/fintech/api/controller/RecurrenceRulesControllerTest.java`

**Interfaces:**
- Consumes: `RecurrenceRule`, `RecurrenceRuleRepository`, `RecurrenceStatus` (Task 1); `@ValidRrule` (Task 2); `AccountRepository.findByIdAndTenant`, `CategoryRepository.findByIdAndTenantIdAndDeletedAtIsNull` (existentes).
- Produces:
  - `RecurrenceRuleCreateDTO(String description, BigDecimal baseAmount, TransactionType type, UUID categoryId, UUID accountId, String rrule, LocalDate startDate)`.
  - `RecurrenceRulePatchDTO(String description, BigDecimal baseAmount)`.
  - `RecurrenceRuleResponseDTO(UUID id, String description, BigDecimal baseAmount, TransactionType type, UUID categoryId, String categoryName, UUID accountId, String accountName, String rrule, LocalDate startDate, RecurrenceStatus status)` + `fromEntity`.
  - `RecurrenceRuleService.create/findAll/findById/patch/cancel`.

- [ ] **Step 1: Adicionar paths e schemas no openapi.yaml**

In `api-spec/openapi.yaml`, em `paths:`, adicione (tag `recurrence-rules` para o Orval gerar `recurrence-rules.service.ts`):

```yaml
  /api/recurrence-rules:
    get:
      tags: [recurrence-rules]
      operationId: listRecurrenceRules
      summary: Lista as regras de recorrência do tenant
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/RecurrenceRuleResponse' }
    post:
      tags: [recurrence-rules]
      operationId: createRecurrenceRule
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/RecurrenceRuleCreate' }
      responses:
        '201':
          description: Criada
          content:
            application/json:
              schema: { $ref: '#/components/schemas/RecurrenceRuleResponse' }
  /api/recurrence-rules/{id}:
    get:
      tags: [recurrence-rules]
      operationId: getRecurrenceRule
      parameters:
        - { name: id, in: path, required: true, schema: { type: string, format: uuid } }
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: { $ref: '#/components/schemas/RecurrenceRuleResponse' }
    patch:
      tags: [recurrence-rules]
      operationId: patchRecurrenceRule
      parameters:
        - { name: id, in: path, required: true, schema: { type: string, format: uuid } }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/RecurrenceRulePatch' }
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: { $ref: '#/components/schemas/RecurrenceRuleResponse' }
    delete:
      tags: [recurrence-rules]
      operationId: cancelRecurrenceRule
      parameters:
        - { name: id, in: path, required: true, schema: { type: string, format: uuid } }
      responses:
        '204': { description: Cancelada }
```

In `components: schemas:`, adicione:

```yaml
    RecurrenceRuleCreate:
      type: object
      required: [description, baseAmount, type, accountId, rrule, startDate]
      properties:
        description: { type: string, maxLength: 255 }
        baseAmount:  { type: number }
        type:        { type: string, enum: [INCOME, EXPENSE] }
        categoryId:  { type: string, format: uuid, nullable: true }
        accountId:   { type: string, format: uuid }
        rrule:       { type: string, example: 'FREQ=MONTHLY;BYMONTHDAY=15' }
        startDate:   { type: string, format: date }
    RecurrenceRulePatch:
      type: object
      properties:
        description: { type: string, maxLength: 255 }
        baseAmount:  { type: number }
    RecurrenceRuleResponse:
      type: object
      required: [id, description, baseAmount, type, accountId, accountName, rrule, startDate, status]
      properties:
        id:           { type: string, format: uuid }
        description:   { type: string }
        baseAmount:    { type: number }
        type:          { type: string, enum: [INCOME, EXPENSE] }
        categoryId:    { type: string, format: uuid, nullable: true }
        categoryName:  { type: string, nullable: true }
        accountId:     { type: string, format: uuid }
        accountName:   { type: string }
        rrule:         { type: string }
        startDate:     { type: string, format: date }
        status:        { type: string, enum: [ACTIVE, CANCELLED] }
```

- [ ] **Step 2: Gerar as interfaces e ver compilar**

Run: `cd backend && ./mvnw generate-sources`
Expected: gera `com.fintech.api.openapi.RecurrenceRulesApi` em `target/`.

- [ ] **Step 3: Criar os DTOs**

Create `backend/src/main/java/com/fintech/api/dto/recurrence/RecurrenceRuleCreateDTO.java`:

```java
package com.fintech.api.dto.recurrence;

import com.fintech.api.domain.enums.TransactionType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RecurrenceRuleCreateDTO(
        @NotBlank @Size(max = 255) String description,
        @NotNull @DecimalMin("0.01") BigDecimal baseAmount,
        @NotNull TransactionType type,
        UUID categoryId,
        @NotNull UUID accountId,
        @NotBlank @ValidRrule String rrule,
        @NotNull LocalDate startDate) {
}
```

Create `backend/src/main/java/com/fintech/api/dto/recurrence/RecurrenceRulePatchDTO.java`:

```java
package com.fintech.api.dto.recurrence;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record RecurrenceRulePatchDTO(
        @Size(max = 255) String description,
        @DecimalMin("0.01") BigDecimal baseAmount) {
}
```

Create `backend/src/main/java/com/fintech/api/dto/recurrence/RecurrenceRuleResponseDTO.java`:

```java
package com.fintech.api.dto.recurrence;

import com.fintech.api.domain.enums.RecurrenceStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.recurrence.RecurrenceRule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RecurrenceRuleResponseDTO(
        UUID id, String description, BigDecimal baseAmount, TransactionType type,
        UUID categoryId, String categoryName, UUID accountId, String accountName,
        String rrule, LocalDate startDate, RecurrenceStatus status) {

    public static RecurrenceRuleResponseDTO fromEntity(RecurrenceRule r) {
        var cat = r.getCategory();
        return new RecurrenceRuleResponseDTO(
                r.getId(), r.getDescription(), r.getBaseAmount(), r.getType(),
                cat != null ? cat.getId() : null, cat != null ? cat.getName() : null,
                r.getAccount().getId(), r.getAccount().getName(),
                r.getRrule(), r.getStartDate(), r.getStatus());
    }
}
```

- [ ] **Step 4: Escrever o teste de controller (deve falhar)**

Create `backend/src/test/java/com/fintech/api/controller/RecurrenceRulesControllerTest.java`. Siga o padrão MockMvc + `@SpringBootTest` + autenticação de teste já usado nos outros `*ControllerTest`. Cenários mínimos:

```java
// Estrutura (ajuste imports/base de auth ao padrão existente no projeto):
// - cria regra válida → 201 + corpo com id e status ACTIVE
// - cria com rrule fora do subconjunto ("FREQ=WEEKLY;BYDAY=MO") → 400
// - lista → contém a criada
// - cancela (DELETE) → 204; GET subsequente mostra status CANCELLED
// - regra de outro tenant (id inexistente p/ o tenant) no GET/{id} → 404
//
// Use o helper de autenticação do projeto (token JWT de admin@test.com do seed_base).
```

> **Nota ao implementador:** copie o boilerplate de autenticação e setup de um `*ControllerTest` existente (ex.: o de transações ou de contas). Não invente um novo mecanismo de auth.

- [ ] **Step 5: Rodar e ver falhar**

Run: `cd backend && ./mvnw test -Dtest=RecurrenceRulesControllerTest`
Expected: FALHA (controller/service ainda não existem).

- [ ] **Step 6: Implementar o `RecurrenceRuleService`**

Create `backend/src/main/java/com/fintech/api/service/RecurrenceRuleService.java`:

```java
package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.RecurrenceStatus;
import com.fintech.api.domain.recurrence.RecurrenceRule;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.recurrence.RecurrenceRuleCreateDTO;
import com.fintech.api.dto.recurrence.RecurrenceRulePatchDTO;
import com.fintech.api.dto.recurrence.RecurrenceRuleResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.RecurrenceRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RecurrenceRuleService {

    private final RecurrenceRuleRepository repository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;

    @Transactional
    public RecurrenceRuleResponseDTO create(RecurrenceRuleCreateDTO dto, User user) {
        Account account = resolveAccount(dto.accountId(), user);
        Category category = resolveCategory(dto.categoryId(), user);
        RecurrenceRule rule = repository.save(RecurrenceRule.builder()
                .tenant(user.getTenant())
                .description(dto.description())
                .baseAmount(dto.baseAmount())
                .type(dto.type())
                .category(category)
                .account(account)
                .rrule(dto.rrule())
                .startDate(dto.startDate())
                .status(RecurrenceStatus.ACTIVE)
                .createdBy(user)
                .build());
        return RecurrenceRuleResponseDTO.fromEntity(rule);
    }

    @Transactional(readOnly = true)
    public List<RecurrenceRuleResponseDTO> findAll(User user) {
        return repository.findByTenantAndStatus(user.getTenant(), RecurrenceStatus.ACTIVE)
                .stream().map(RecurrenceRuleResponseDTO::fromEntity).toList();
    }

    @Transactional(readOnly = true)
    public RecurrenceRuleResponseDTO findById(UUID id, User user) {
        return RecurrenceRuleResponseDTO.fromEntity(findOwned(id, user));
    }

    @Transactional
    public RecurrenceRuleResponseDTO patch(UUID id, RecurrenceRulePatchDTO dto, User user) {
        RecurrenceRule rule = findOwned(id, user);
        if (dto.description() != null) rule.setDescription(dto.description());
        if (dto.baseAmount() != null)  rule.setBaseAmount(dto.baseAmount());
        return RecurrenceRuleResponseDTO.fromEntity(rule);
    }

    @Transactional
    public void cancel(UUID id, User user) {
        findOwned(id, user).setStatus(RecurrenceStatus.CANCELLED);
    }

    private RecurrenceRule findOwned(UUID id, User user) {
        return repository.findByIdAndTenant(id, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Regra de recorrência não encontrada."));
    }

    private Account resolveAccount(UUID accountId, User user) {
        return accountRepository.findByIdAndTenant(accountId, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Conta não encontrada."));
    }

    private Category resolveCategory(UUID categoryId, User user) {
        if (categoryId == null) return null;
        return categoryRepository.findByIdAndTenantIdAndDeletedAtIsNull(categoryId, user.getTenant().getId())
                .orElseThrow(() -> new EntityNotFoundException("Categoria não encontrada."));
    }
}
```

- [ ] **Step 7: Implementar o controller**

Create `backend/src/main/java/com/fintech/api/controller/RecurrenceRulesController.java`:

```java
package com.fintech.api.controller;

import com.fintech.api.config.SecurityUtils;
import com.fintech.api.dto.recurrence.RecurrenceRuleCreateDTO;
import com.fintech.api.dto.recurrence.RecurrenceRulePatchDTO;
import com.fintech.api.dto.recurrence.RecurrenceRuleResponseDTO;
import com.fintech.api.service.RecurrenceRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/recurrence-rules")
@RequiredArgsConstructor
public class RecurrenceRulesController {

    private final RecurrenceRuleService service;

    @GetMapping
    public ResponseEntity<List<RecurrenceRuleResponseDTO>> list() {
        return ResponseEntity.ok(service.findAll(SecurityUtils.currentUser()));
    }

    @PostMapping
    public ResponseEntity<RecurrenceRuleResponseDTO> create(@RequestBody @Valid RecurrenceRuleCreateDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(dto, SecurityUtils.currentUser()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RecurrenceRuleResponseDTO> get(@PathVariable UUID id) {
        return ResponseEntity.ok(service.findById(id, SecurityUtils.currentUser()));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<RecurrenceRuleResponseDTO> patch(
            @PathVariable UUID id, @RequestBody @Valid RecurrenceRulePatchDTO dto) {
        return ResponseEntity.ok(service.patch(id, dto, SecurityUtils.currentUser()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        service.cancel(id, SecurityUtils.currentUser());
        return ResponseEntity.noContent().build();
    }
}
```

> **Nota:** este controller não implementa a interface gerada (`RecurrenceRulesApi`) para manter as assinaturas livres do parâmetro de usuário (os controllers do projeto leem o usuário via `SecurityUtils`, não pelos parâmetros gerados). A spec openapi continua sendo a fonte do contrato e do client do frontend. Se o padrão do projeto exigir `implements`, alinhe as assinaturas à interface gerada.

- [ ] **Step 8: Rodar e ver passar**

Run: `cd backend && ./mvnw test -Dtest=RecurrenceRulesControllerTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add api-spec/openapi.yaml \
        backend/src/main/java/com/fintech/api/dto/recurrence/RecurrenceRuleCreateDTO.java \
        backend/src/main/java/com/fintech/api/dto/recurrence/RecurrenceRulePatchDTO.java \
        backend/src/main/java/com/fintech/api/dto/recurrence/RecurrenceRuleResponseDTO.java \
        backend/src/main/java/com/fintech/api/service/RecurrenceRuleService.java \
        backend/src/main/java/com/fintech/api/controller/RecurrenceRulesController.java \
        backend/src/test/java/com/fintech/api/controller/RecurrenceRulesControllerTest.java
git commit -m "feat(recorrencia): adiciona CRUD de regras de recorrência"
```

---

## Task 5: Confirmar e Pular ocorrências

**Files:**
- Modify: `api-spec/openapi.yaml`
- Create: `backend/src/main/java/com/fintech/api/dto/recurrence/ConfirmOccurrenceDTO.java`
- Modify: `backend/src/main/java/com/fintech/api/service/TransactionService.java`
- Modify: `backend/src/main/java/com/fintech/api/service/RecurrenceRuleService.java`
- Modify: `backend/src/main/java/com/fintech/api/controller/RecurrenceRulesController.java`
- Test: `backend/src/test/java/com/fintech/api/controller/RecurrenceOccurrenceControllerTest.java`

**Interfaces:**
- Consumes: `RecurrenceProjectionService` não é necessário aqui; usa `RecurrenceRuleRepository.findByIdAndTenant`, `RecurrenceExceptionRepository`, `TransactionRepository` (Task 1).
- Produces:
  - `ConfirmOccurrenceDTO(BigDecimal amount, LocalDate date)` — ambos opcionais (override).
  - `TransactionService.materializeFromRule(RecurrenceRule rule, LocalDate occurrence, BigDecimal amount, LocalDate date, User user) : TransactionResponseDTO`.
  - `RecurrenceRuleService.confirmOccurrence(UUID ruleId, LocalDate occurrence, ConfirmOccurrenceDTO body, User) : TransactionResponseDTO`; `skipOccurrence(UUID ruleId, LocalDate occurrence, User) : void`.

- [ ] **Step 1: Adicionar os endpoints de ocorrência no openapi.yaml**

In `api-spec/openapi.yaml` `paths:`, adicione:

```yaml
  /api/recurrence-rules/{id}/occurrences/{date}/confirm:
    post:
      tags: [recurrence-rules]
      operationId: confirmOccurrence
      parameters:
        - { name: id,   in: path, required: true, schema: { type: string, format: uuid } }
        - { name: date, in: path, required: true, schema: { type: string, format: date } }
      requestBody:
        required: false
        content:
          application/json:
            schema: { $ref: '#/components/schemas/ConfirmOccurrence' }
      responses:
        '201':
          description: Ocorrência materializada
          content:
            application/json:
              schema: { $ref: '#/components/schemas/TransactionResponse' }
  /api/recurrence-rules/{id}/occurrences/{date}/skip:
    post:
      tags: [recurrence-rules]
      operationId: skipOccurrence
      parameters:
        - { name: id,   in: path, required: true, schema: { type: string, format: uuid } }
        - { name: date, in: path, required: true, schema: { type: string, format: date } }
      responses:
        '204': { description: Ocorrência pulada (EXDATE gravada) }
```

In `components: schemas:` adicione (referencie o schema de transação já existente como `TransactionResponse` — confirme o nome real no arquivo):

```yaml
    ConfirmOccurrence:
      type: object
      properties:
        amount: { type: number, nullable: true }
        date:   { type: string, format: date, nullable: true }
```

- [ ] **Step 2: Criar o DTO**

Create `backend/src/main/java/com/fintech/api/dto/recurrence/ConfirmOccurrenceDTO.java`:

```java
package com.fintech.api.dto.recurrence;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.time.LocalDate;

// Ambos opcionais: confirmar sem corpo usa base_amount da regra e a própria data da ocorrência.
public record ConfirmOccurrenceDTO(
        @DecimalMin("0.01") BigDecimal amount,
        LocalDate date) {
}
```

- [ ] **Step 3: Escrever o teste (deve falhar)**

Create `backend/src/test/java/com/fintech/api/controller/RecurrenceOccurrenceControllerTest.java`. Cenários (MockMvc, padrão de auth do projeto):

```java
// - POST .../occurrences/2026-08-10/confirm sem corpo → 201; transação criada com
//   recurrenceRuleId + recurrenceOccurrence=2026-08-10, amount = base_amount da regra.
// - POST confirm com body {amount: 150} → 201; transação com amount 150.
// - POST confirm na MESMA ocorrência de novo → 409 (unique parcial).
// - POST .../occurrences/2026-08-10/skip → 204; existsByRuleIdAndOccurrenceDate == true.
// - POST confirm/skip em regra de outro tenant → 404.
// Use uma conta CHECKING do seed_base (evita exigir fatura). Um caso adicional com conta
// CREDIT_CARD valida que a fatura é resolvida pela lógica existente.
```

- [ ] **Step 4: Rodar e ver falhar**

Run: `cd backend && ./mvnw test -Dtest=RecurrenceOccurrenceControllerTest`
Expected: FALHA (métodos/endpoints ausentes).

- [ ] **Step 5: Adicionar `materializeFromRule` ao `TransactionService`**

In `backend/src/main/java/com/fintech/api/service/TransactionService.java`, adicione o import `com.fintech.api.domain.recurrence.RecurrenceRule` e o método (reusa a resolução de fatura de cartão já existente no `create`):

```java
    // Materializa UMA ocorrência de regra como transação real. Reusa a resolução de fatura
    // de cartão (resolveInvoiceMonth/getOrCreate) — se a conta da regra for CREDIT_CARD, a
    // transação nasce amarrada à fatura correta, sem caminho novo.
    @Transactional
    public TransactionResponseDTO materializeFromRule(
            RecurrenceRule rule, LocalDate occurrence, BigDecimal amountOverride, LocalDate dateOverride, User user) {
        Account account = rule.getAccount();
        BigDecimal amount = amountOverride != null ? amountOverride : rule.getBaseAmount();
        LocalDate date = dateOverride != null ? dateOverride : occurrence;

        Invoice invoice = null;
        if (AccountType.CREDIT_CARD.equals(account.getType())) {
            int closingDay = creditCardDetailsRepository.findByAccount(account)
                    .orElseThrow(() -> new EntityNotFoundException("Detalhes do cartão não encontrados para a conta."))
                    .getClosingDay();
            YearMonth invoiceMonth = resolveInvoiceMonth(date, closingDay);
            invoice = invoiceService.getOrCreate(account, invoiceMonth.getYear(), invoiceMonth.getMonthValue());
        }

        Transaction t = repository.save(Transaction.builder()
                .description(rule.getDescription())
                .amount(amount)
                .date(date)
                .type(rule.getType())
                .status(TransactionStatus.PENDING)
                .category(rule.getCategory())
                .account(account)
                .invoice(invoice)
                .recurrenceRule(rule)
                .recurrenceOccurrence(occurrence)
                .tenant(user.getTenant())
                .user(user)
                .build());
        return TransactionResponseDTO.fromEntity(t);
    }
```

- [ ] **Step 6: Adicionar `confirmOccurrence`/`skipOccurrence` ao `RecurrenceRuleService`**

In `RecurrenceRuleService.java`, injete os colaboradores e adicione os métodos:

```java
    // adicionar aos campos finais:
    private final TransactionService transactionService;
    private final com.fintech.api.repository.RecurrenceExceptionRepository exceptionRepository;
```

```java
    @Transactional
    public com.fintech.api.dto.transaction.TransactionResponseDTO confirmOccurrence(
            UUID ruleId, java.time.LocalDate occurrence,
            com.fintech.api.dto.recurrence.ConfirmOccurrenceDTO body, User user) {
        RecurrenceRule rule = findOwned(ruleId, user);
        java.math.BigDecimal amount = body != null ? body.amount() : null;
        java.time.LocalDate date    = body != null ? body.date()   : null;
        // A unique parcial (rule, occurrence) no banco impede confirmar duas vezes →
        // DataIntegrityViolationException; o GlobalExceptionHandler já mapeia conflito.
        // Guard explícito para mensagem amigável:
        if (transactionService.existsMaterializedOccurrence(ruleId, occurrence)) {
            throw new com.fintech.api.exception.BusinessConflictException(
                    "Esta ocorrência já foi confirmada.");
        }
        return transactionService.materializeFromRule(rule, occurrence, amount, date, user);
    }

    @Transactional
    public void skipOccurrence(UUID ruleId, java.time.LocalDate occurrence, User user) {
        RecurrenceRule rule = findOwned(ruleId, user);
        if (!exceptionRepository.existsByRuleIdAndOccurrenceDate(ruleId, occurrence)) {
            exceptionRepository.save(com.fintech.api.domain.recurrence.RecurrenceException.builder()
                    .rule(rule).occurrenceDate(occurrence).build());
        }
    }
```

Add to `TransactionService` the guard query (delegando ao repositório):

```java
    @Transactional(readOnly = true)
    public boolean existsMaterializedOccurrence(UUID ruleId, LocalDate occurrence) {
        return repository.existsByRecurrenceRuleIdAndRecurrenceOccurrence(ruleId, occurrence);
    }
```

Add to `TransactionRepository`:

```java
    boolean existsByRecurrenceRuleIdAndRecurrenceOccurrence(java.util.UUID ruleId, java.time.LocalDate occurrence);
```

- [ ] **Step 7: Adicionar os endpoints ao controller**

In `RecurrenceRulesController.java`, adicione:

```java
    @PostMapping("/{id}/occurrences/{date}/confirm")
    public ResponseEntity<com.fintech.api.dto.transaction.TransactionResponseDTO> confirm(
            @PathVariable UUID id,
            @org.springframework.web.bind.annotation.PathVariable
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate date,
            @RequestBody(required = false) @Valid com.fintech.api.dto.recurrence.ConfirmOccurrenceDTO body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.confirmOccurrence(id, date, body, SecurityUtils.currentUser()));
    }

    @PostMapping("/{id}/occurrences/{date}/skip")
    public ResponseEntity<Void> skip(
            @PathVariable UUID id,
            @org.springframework.web.bind.annotation.PathVariable
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate date) {
        service.skipOccurrence(id, date, SecurityUtils.currentUser());
        return ResponseEntity.noContent().build();
    }
```

- [ ] **Step 8: Rodar e ver passar**

Run: `cd backend && ./mvnw test -Dtest=RecurrenceOccurrenceControllerTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add api-spec/openapi.yaml backend/src/main/java/com/fintech/api/dto/recurrence/ConfirmOccurrenceDTO.java \
        backend/src/main/java/com/fintech/api/service/TransactionService.java \
        backend/src/main/java/com/fintech/api/service/RecurrenceRuleService.java \
        backend/src/main/java/com/fintech/api/controller/RecurrenceRulesController.java \
        backend/src/main/java/com/fintech/api/repository/TransactionRepository.java \
        backend/src/test/java/com/fintech/api/controller/RecurrenceOccurrenceControllerTest.java
git commit -m "feat(recorrencia): adiciona confirmar e pular ocorrências"
```

---

## Task 6: `includeProjected` na lista de transações

**Files:**
- Modify: `api-spec/openapi.yaml`
- Modify: `backend/src/main/java/com/fintech/api/dto/transaction/TransactionResponseDTO.java`
- Modify: `backend/src/main/java/com/fintech/api/service/TransactionService.java`
- Modify: `backend/src/main/java/com/fintech/api/controller/TransactionController.java`
- Test: `backend/src/test/java/com/fintech/api/service/TransactionProjectionMergeTest.java`

**Interfaces:**
- Consumes: `RecurrenceProjectionService.project` (Task 3); `ProjectedOccurrence` (Task 3).
- Produces:
  - `TransactionResponseDTO` ganha `boolean projected`, `UUID recurrenceRuleId`, `LocalDate occurrenceDate` + `fromProjection(ProjectedOccurrence)`.
  - `TransactionService.findAll(... , boolean includeProjected)` — sobrecarga que mescla projeção quando a janela é atual/futura.

- [ ] **Step 1: Estender o `TransactionResponseDTO`**

In `TransactionResponseDTO.java`: adicione os 3 campos ao record (no fim da lista de componentes) e atualize `fromEntity` para passar `false, t.getRecurrenceRule()!=null ? id : null, t.getRecurrenceOccurrence()`; e crie `fromProjection`:

```java
        // ... campos existentes ...,
        boolean projected,
        UUID recurrenceRuleId,
        LocalDate occurrenceDate
```

No `fromEntity`, acrescente os 3 argumentos finais:

```java
                false,
                t.getRecurrenceRule() != null ? t.getRecurrenceRule().getId() : null,
                t.getRecurrenceOccurrence()
```

Adicione a factory de fantasma (id nulo — não existe no banco):

```java
    public static TransactionResponseDTO fromProjection(
            com.fintech.api.service.recurrence.ProjectedOccurrence o) {
        return new TransactionResponseDTO(
                null, o.description(), o.amount(), o.occurrenceDate(), o.type(),
                com.fintech.api.domain.enums.TransactionStatus.PENDING,
                null, null, null,
                o.categoryName(), o.categoryId(), false, o.categoryName(), o.categoryIcon(),
                o.accountName(), o.accountId(),
                null, null, null, null, null, null,
                true, o.ruleId(), o.occurrenceDate());
    }
```

> **Nota:** a ordem/quantidade exata dos argumentos deve casar com o record. Conte os campos atuais (22) + 3 novos = 25; preencha `null` nos que não se aplicam à fantasma (installment*, transfer*, invoice*).

- [ ] **Step 2: Escrever o teste de merge (deve falhar)**

Create `backend/src/test/java/com/fintech/api/service/TransactionProjectionMergeTest.java` (Mockito). Verifica que com `includeProjected=true` a fantasma entra na lista ordenada por data desc e que `projected=true`; com `false`, não:

```java
// - mock repository.findAllByTenantWithFilters → 1 transação real em 2026-08-05
// - mock projectionService.project → 1 ProjectedOccurrence em 2026-08-15
// - findAll(..., includeProjected=true) → 2 itens, ordenado desc (15 antes de 05),
//   o de 15 com projected=true e id=null
// - findAll(..., includeProjected=false) → 1 item (só a real)
```

- [ ] **Step 3: Rodar e ver falhar**

Run: `cd backend && ./mvnw test -Dtest=TransactionProjectionMergeTest`
Expected: FALHA (sobrecarga ausente).

- [ ] **Step 4: Implementar o merge no `TransactionService`**

Injete `RecurrenceProjectionService projectionService` no construtor (campo final). Renomeie a `findAll` atual para a sobrecarga com `includeProjected` e capture os reais numa variável em vez de `return` direto; adicione a `findAll` antiga delegando com `false`. O branch de `invoiceId` **não** projeta (é específico de fatura) — mantém o early-return:

```java
    @Transactional(readOnly = true)
    public List<TransactionResponseDTO> findAll(User user, UUID invoiceId, List<UUID> accountIds,
            TransactionStatus status, TransactionType type, LocalDate startDate, LocalDate endDate) {
        return findAll(user, invoiceId, accountIds, status, type, startDate, endDate, false);
    }

    @Transactional(readOnly = true)
    public List<TransactionResponseDTO> findAll(User user, UUID invoiceId, List<UUID> accountIds,
            TransactionStatus status, TransactionType type, LocalDate startDate, LocalDate endDate,
            boolean includeProjected) {
        if ((startDate == null) != (endDate == null)) {
            throw new IllegalArgumentException("startDate e endDate devem ser informados juntos ou omitidos juntos");
        }
        if (invoiceId != null) {
            Invoice invoice = invoiceService.findByIdAndTenant(invoiceId, user.getTenant());
            return repository.findAllByTenantAndInvoiceWithDetails(user.getTenant(), invoice)
                    .stream().map(TransactionResponseDTO::fromEntity).toList(); // sem projeção em fatura
        }

        LocalDate effectiveStart = startDate != null ? startDate : LocalDate.of(1000, 1, 1);
        LocalDate effectiveEnd   = endDate   != null ? endDate   : LocalDate.of(9999, 12, 31);
        List<UUID> effectiveAccountIds = (accountIds == null || accountIds.isEmpty()) ? List.of() : accountIds;
        int accountIdCount = effectiveAccountIds.size();

        List<TransactionResponseDTO> reais = repository.findAllByTenantWithFilters(
                        user.getTenant(), effectiveAccountIds, accountIdCount, status, type, effectiveStart, effectiveEnd)
                .stream()
                .sorted(Comparator.comparing(this::effectiveSortDate, Comparator.reverseOrder()))
                .map(TransactionResponseDTO::fromEntity)
                .toList();

        if (!includeProjected || startDate == null || endDate == null) {
            return reais;
        }
        // Projeta a janela e aplica os mesmos filtros de conta/tipo em memória (fantasma é sempre PENDING).
        List<TransactionResponseDTO> fantasmas = projectionService
                .project(user.getTenant(), startDate, endDate).stream()
                .filter(o -> effectiveAccountIds.isEmpty() || effectiveAccountIds.contains(o.accountId()))
                .filter(o -> type == null || type == o.type())
                .filter(o -> status == null || status == TransactionStatus.PENDING)
                .map(TransactionResponseDTO::fromProjection)
                .toList();

        return java.util.stream.Stream.concat(reais.stream(), fantasmas.stream())
                .sorted(Comparator.comparing(TransactionResponseDTO::date, Comparator.reverseOrder()))
                .toList();
    }
```

> **Nota:** `includeProjected` só projeta quando há `startDate`+`endDate` (a janela). Sem janela, devolve só reais — a projeção sempre precisa de limites (Global Constraint). Para fantasmas, `effectiveSortDate` = `occurrenceDate` = `date` do DTO, então ordenar por `date` desc é consistente.

- [ ] **Step 5: Adicionar o parâmetro no openapi e no controller**

In `api-spec/openapi.yaml`, no `operationId: listTransactions` (parâmetros), adicione:

```yaml
        - { name: includeProjected, in: query, required: false, schema: { type: boolean, default: false } }
```

Adicione os 3 campos novos ao schema de resposta de transação (`projected`, `recurrenceRuleId`, `occurrenceDate`) — `projected` boolean, os outros nullable.

In `TransactionController.listTransactions`, adicione o parâmetro e repasse:

```java
            @RequestParam(value = "includeProjected", required = false, defaultValue = "false") boolean includeProjected) {
        return ResponseEntity.ok(service.findAll(
                getAuthenticatedUser(), invoiceId, accountIds, status, type, startDate, endDate, includeProjected));
```

- [ ] **Step 6: Rodar e ver passar**

Run: `cd backend && ./mvnw test -Dtest=TransactionProjectionMergeTest`
Expected: PASS. Depois rode a suíte toda: `./mvnw test` → tudo verde.

- [ ] **Step 7: Commit**

```bash
git add api-spec/openapi.yaml \
        backend/src/main/java/com/fintech/api/dto/transaction/TransactionResponseDTO.java \
        backend/src/main/java/com/fintech/api/service/TransactionService.java \
        backend/src/main/java/com/fintech/api/controller/TransactionController.java \
        backend/src/test/java/com/fintech/api/service/TransactionProjectionMergeTest.java
git commit -m "feat(recorrencia): mescla linhas fantasma em GET /api/transactions via includeProjected"
```

---

## Task 7: Dataset — seed V20, seed_base e HTTP collection

**Files:**
- Create: `backend/src/main/resources/db/seed/V20__seed_dev_recurrence.sql`
- Modify: `backend/src/test/resources/sql/seed_base.sql`
- Modify: `docs/http/seed-dataset.http`

**Interfaces:** nenhuma de código — dados.

- [ ] **Step 1: Criar o seed dev V20**

Create `backend/src/main/resources/db/seed/V20__seed_dev_recurrence.sql`. Use UUIDs predefinidos na série da Família Costa (verifique o padrão no `V13`/spec de dataset) e referencie tenant/conta/categoria já existentes no V13:

```sql
-- Seed dev — recorrências da Família Costa. Perfil 'dev'.
-- Netflix: assinatura mensal infinita no dia 15, na conta corrente do Carlos.
INSERT INTO recurrence_rules
    (id, tenant_id, description, base_amount, type, category_id, account_id, rrule, start_date, status, created_by)
VALUES
    ('<uuid-recurrence-netflix>', '<uuid-tenant-costa>', 'Netflix', 55.90, 'EXPENSE',
     '<uuid-categoria-assinaturas>', '<uuid-conta-corrente-carlos>',
     'FREQ=MONTHLY;BYMONTHDAY=15', '2026-01-15', 'ACTIVE', '<uuid-user-carlos>');

-- Exemplo de pulo: a ocorrência de fevereiro foi pulada (academia trancada, etc.).
INSERT INTO recurrence_exceptions (id, rule_id, occurrence_date) VALUES
    ('<uuid-exception-1>', '<uuid-recurrence-netflix>', '2026-02-15');
```

> **Nota ao implementador:** substitua os `<uuid-...>` pelos UUIDs reais do `V13__seed_dev.sql` (tenant, conta corrente do Carlos, categoria adequada, user Carlos) e gere UUIDs novos na série correta para a regra e a exceção. Nunca `gen_random_uuid()` para entidades com cross-reference (regra do dataset).

- [ ] **Step 2: Adicionar 1 regra mínima ao `seed_base.sql`**

In `backend/src/test/resources/sql/seed_base.sql`, adicione uma regra usando os UUIDs já definidos lá (tenant/conta/usuário de teste). Exemplo:

```sql
INSERT INTO recurrence_rules
    (id, tenant_id, description, base_amount, type, account_id, rrule, start_date, status, created_by)
VALUES
    ('<uuid-test-recurrence>', '<uuid-test-tenant>', 'Assinatura Teste', 100.00, 'EXPENSE',
     '<uuid-test-checking-account>', 'FREQ=MONTHLY;BYMONTHDAY=10', '2026-01-10', 'ACTIVE', '<uuid-test-user>');
```

> Use os UUIDs já presentes no `seed_base.sql`. Garanta que exista uma conta CHECKING e uma CREDIT_CARD para os testes da Task 5.

- [ ] **Step 3: Adicionar requests ao `seed-dataset.http`**

In `docs/http/seed-dataset.http`, adicione (após autenticação, reusando a variável de token do arquivo):

```http
### Listar transações com linhas fantasma (mês atual)
GET {{baseUrl}}/api/transactions?startDate=2026-06-01&endDate=2026-06-30&includeProjected=true
Authorization: Bearer {{token}}

### Criar regra de recorrência
POST {{baseUrl}}/api/recurrence-rules
Authorization: Bearer {{token}}
Content-Type: application/json

{ "description": "Spotify", "baseAmount": 21.90, "type": "EXPENSE",
  "accountId": "{{accountId}}", "rrule": "FREQ=MONTHLY;BYMONTHDAY=5", "startDate": "2026-06-05" }

### Confirmar ocorrência (valor ajustado)
POST {{baseUrl}}/api/recurrence-rules/{{ruleId}}/occurrences/2026-06-05/confirm
Authorization: Bearer {{token}}
Content-Type: application/json

{ "amount": 25.90 }

### Pular ocorrência
POST {{baseUrl}}/api/recurrence-rules/{{ruleId}}/occurrences/2026-07-05/skip
Authorization: Bearer {{token}}
```

- [ ] **Step 4: Validar que o perfil dev sobe com o seed**

Run: `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` (com Docker do Postgres de pé) e confirme nos logs que Flyway aplicou `V20` sem erro. Pare o servidor.
Expected: aplicação sobe; `GET /actuator/health` = UP.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/seed/V20__seed_dev_recurrence.sql \
        backend/src/test/resources/sql/seed_base.sql \
        docs/http/seed-dataset.http
git commit -m "test(recorrencia): adiciona seed dev V20, regra em seed_base e requests HTTP"
```

---

## Task 8: Frontend — client + editor de RRULE (rrule.js)

**Files:**
- Modify: `frontend/package.json`
- Create: `frontend/src/app/features/recurrence/rrule-editor/rrule-builder.ts`
- Create: `frontend/src/app/features/recurrence/rrule-editor/rrule-builder.spec.ts`
- Create: `frontend/src/app/features/recurrence/rrule-editor/rrule-editor.ts` (+ `.html`, `.scss`)
- Create: `frontend/src/app/core/services/recurrence.service.ts`

**Interfaces:**
- Produces:
  - `rrule-builder.ts`: `buildRrule(form: RruleForm): string` e `parseRrule(rrule: string): RruleForm`, onde `RruleForm = { frequency: 'MONTHLY'|'YEARLY'; interval: number; dayOfMonth: number | 'LAST'; end: { kind: 'NEVER' } | { kind: 'UNTIL'; date: string } | { kind: 'COUNT'; count: number } }`.
  - `RecurrenceEditor` (componente standalone) emitindo o `rrule` string + preview (signal).
  - `RecurrenceService` (providedIn root) — usa o client Orval gerado.

- [ ] **Step 1: Instalar `rrule` e regenerar o client**

Run:
```bash
cd frontend && npm install rrule
cp ../api-spec/openapi.yaml ../backend/src/main/resources/static/openapi.yaml
npm run api:generate
```
Expected: gera `src/app/core/api/recurrence-rules/*` e os models novos. (Lembre da armadilha Orval: campos de resposta sem `required` viram opcionais → use `!`.)

- [ ] **Step 2: Escrever o teste da lógica pura (deve falhar)**

Create `frontend/src/app/features/recurrence/rrule-editor/rrule-builder.spec.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { buildRrule, parseRrule } from './rrule-builder';

describe('rrule-builder', () => {
  it('monta mensal no dia fixo', () => {
    expect(buildRrule({ frequency: 'MONTHLY', interval: 1, dayOfMonth: 15, end: { kind: 'NEVER' } }))
      .toBe('FREQ=MONTHLY;BYMONTHDAY=15');
  });

  it('monta trimestral', () => {
    expect(buildRrule({ frequency: 'MONTHLY', interval: 3, dayOfMonth: 1, end: { kind: 'NEVER' } }))
      .toBe('FREQ=MONTHLY;INTERVAL=3;BYMONTHDAY=1');
  });

  it('mapeia "último dia" para BYMONTHDAY=-1', () => {
    expect(buildRrule({ frequency: 'MONTHLY', interval: 1, dayOfMonth: 'LAST', end: { kind: 'NEVER' } }))
      .toBe('FREQ=MONTHLY;BYMONTHDAY=-1');
  });

  it('inclui COUNT', () => {
    expect(buildRrule({ frequency: 'MONTHLY', interval: 1, dayOfMonth: 10, end: { kind: 'COUNT', count: 12 } }))
      .toBe('FREQ=MONTHLY;BYMONTHDAY=10;COUNT=12');
  });

  it('parse é inverso de build (round-trip)', () => {
    const form = { frequency: 'MONTHLY', interval: 2, dayOfMonth: 'LAST', end: { kind: 'NEVER' } } as const;
    expect(parseRrule(buildRrule(form))).toEqual(form);
  });
});
```

- [ ] **Step 3: Rodar e ver falhar**

Run: `cd frontend && npx vitest run rrule-builder`
Expected: FALHA (módulo inexistente).

- [ ] **Step 4: Implementar `rrule-builder.ts`**

Create `frontend/src/app/features/recurrence/rrule-editor/rrule-builder.ts`:

```ts
// Lógica pura (sem imports Angular) — testável no Vitest sem TestBed.
// Usa a lib rrule só para validar/normalizar; a montagem do string é direta e previsível.

export type RruleEnd =
  | { kind: 'NEVER' }
  | { kind: 'UNTIL'; date: string } // ISO yyyy-mm-dd
  | { kind: 'COUNT'; count: number };

export interface RruleForm {
  frequency: 'MONTHLY' | 'YEARLY';
  interval: number;
  dayOfMonth: number | 'LAST';
  end: RruleEnd;
}

export function buildRrule(form: RruleForm): string {
  const parts: string[] = [`FREQ=${form.frequency}`];
  if (form.interval > 1) parts.push(`INTERVAL=${form.interval}`);
  parts.push(`BYMONTHDAY=${form.dayOfMonth === 'LAST' ? -1 : form.dayOfMonth}`);
  if (form.end.kind === 'COUNT') parts.push(`COUNT=${form.end.count}`);
  if (form.end.kind === 'UNTIL') parts.push(`UNTIL=${form.end.date.replace(/-/g, '')}T000000Z`);
  return parts.join(';');
}

export function parseRrule(rrule: string): RruleForm {
  const map = new Map(
    rrule.split(';').map((p) => {
      const [k, v] = p.split('=');
      return [k, v] as const;
    }),
  );
  const byday = Number(map.get('BYMONTHDAY'));
  let end: RruleEnd = { kind: 'NEVER' };
  if (map.has('COUNT')) end = { kind: 'COUNT', count: Number(map.get('COUNT')) };
  else if (map.has('UNTIL')) {
    const u = map.get('UNTIL')!; // yyyymmddT...
    end = { kind: 'UNTIL', date: `${u.slice(0, 4)}-${u.slice(4, 6)}-${u.slice(6, 8)}` };
  }
  return {
    frequency: (map.get('FREQ') as 'MONTHLY' | 'YEARLY') ?? 'MONTHLY',
    interval: map.has('INTERVAL') ? Number(map.get('INTERVAL')) : 1,
    dayOfMonth: byday === -1 ? 'LAST' : byday,
    end,
  };
}
```

- [ ] **Step 5: Rodar e ver passar**

Run: `cd frontend && npx vitest run rrule-builder`
Expected: PASS (5 testes).

- [ ] **Step 6: Criar o componente editor e o service**

Create `rrule-editor.ts` (standalone, signals, Material): inputs de frequência/intervalo/dia (com opção "Último dia")/fim; um `output()` `rruleChange` emitindo `buildRrule(form())`; um `computed()` com preview das próximas 3 datas via `RRule.fromString('RRULE:' + rrule).all((_, i) => i < 3)`. Mantenha o componente enxuto.

Create `core/services/recurrence.service.ts` (`providedIn: 'root'`) encapsulando o serviço Orval gerado (`RecurrenceRulesService`) com métodos `list()`, `create()`, `patch()`, `cancel()`, `confirm()`, `skip()`.

> **Nota:** componentes Angular exigem `ng test` (não `npx vitest` cru) — vide summary.md. A lógica testável já está em `rrule-builder.ts`; o componente é casca fina.

- [ ] **Step 7: Commit**

```bash
git add frontend/package.json frontend/package-lock.json \
        frontend/src/app/features/recurrence/rrule-editor/ \
        frontend/src/app/core/services/recurrence.service.ts \
        frontend/src/app/core/api/ \
        backend/src/main/resources/static/openapi.yaml
git commit -m "feat(recorrencia): adiciona editor de RRULE e service no frontend"
```

---

## Task 9: Frontend — toggle "repetir" no formulário de transação

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-form/transaction-form.ts` (+ `.html`)

**Interfaces:**
- Consumes: `RecurrenceEditor`, `RecurrenceService.create` (Task 8).

- [ ] **Step 1: Adicionar o toggle e o editor ao template**

In `transaction-form.html`, adicione um `mat-slide-toggle` "Repetir" controlado por um `signal` `repeat`. Quando ligado (`@if (repeat())`), renderize `<app-rrule-editor (rruleChange)="rrule.set($event)">`.

- [ ] **Step 2: Ramo de criação de regra no submit**

In `transaction-form.ts`, no submit: se `repeat()` está ligado e há `rrule()`, em vez de (ou além de) criar a transação avulsa, chame `recurrenceService.create({...})` com `startDate = date` e `baseAmount = amount`. Decisão de produto da spec: cria a regra e materializa a 1ª ocorrência (a própria transação). Implemente como: criar a regra, depois confirmar a ocorrência de `startDate` (`recurrenceService.confirm(ruleId, startDate, { amount })`).

- [ ] **Step 3: Verificar build e specs existentes**

Run: `cd frontend && npm run build && npm test`
Expected: build OK; specs de `transaction-form` continuam verdes (ajuste o spec se o submit mudou de contrato).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-form/
git commit -m "feat(recorrencia): adiciona toggle repetir no formulário de transação"
```

---

## Task 10: Frontend — fantasma + Confirmar/Pular na lista

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts` (+ `.spec.ts`)
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.ts` (+ `.html`, `.scss`)

**Interfaces:**
- Consumes: `GET /api/transactions?includeProjected=true` (Task 6); `RecurrenceService.confirm/skip` (Task 8).

- [ ] **Step 1: Teste da lógica pura de rotulagem (deve falhar)**

In `transaction-list.utils.spec.ts`, adicione um teste para uma função pura `isGhost(row): boolean` (= `row.projected === true`) e, se houver agrupamento, que a fantasma entra no grupo do mês certo. Mantenha pequeno.

- [ ] **Step 2: Implementar a flag/estilo e ações**

- `transaction-list.utils.ts`: `export const isGhost = (r: { projected?: boolean }) => r.projected === true;`
- `transaction-list.ts`: ligar `includeProjected=true` na chamada do mês atual/futuro; armazenar `ruleId`/`occurrenceDate` da linha. Ações `confirmar(row)` → `recurrenceService.confirm(row.recurrenceRuleId!, row.occurrenceDate!, {})` então recarregar; `pular(row)` → `recurrenceService.skip(...)` então recarregar.
- `transaction-list.html`: nas linhas com `isGhost(row)`, aplicar classe `.ghost` e mostrar botões "Confirmar"/"Pular" em vez das ações normais.
- `transaction-list.scss`: `.ghost { opacity: .6; font-style: italic; }` + um ícone (ex.: `schedule`).

- [ ] **Step 3: Rodar testes**

Run: `cd frontend && npx vitest run transaction-list.utils && npm run build`
Expected: PASS + build OK.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-list/
git commit -m "feat(recorrencia): renderiza linhas fantasma com Confirmar/Pular na lista"
```

---

## Task 11: Frontend — feature "Recorrências" (lista/criar/editar/cancelar)

**Files:**
- Create: `frontend/src/app/features/recurrence/recurrence-list/recurrence-list.ts` (+ `.html`, `.scss`)
- Create: `frontend/src/app/features/recurrence/recurrence.routes.ts`
- Modify: as rotas raiz da app (registrar `recurrences/` lazy) e o sidenav.

**Interfaces:**
- Consumes: `RecurrenceService` (Task 8), `RecurrenceEditor` (Task 8).

- [ ] **Step 1: Componente de listagem**

`recurrence-list.ts` (standalone, signals): carrega `recurrenceService.list()` num `signal`; tabela Material com descrição, valor, conta, regra legível e ações Editar/Cancelar. Criar abre um dialog com o `RecurrenceEditor` + campos de descrição/valor/conta/categoria/tipo; submit → `create()`.

- [ ] **Step 2: Rota lazy**

`recurrence.routes.ts`:

```ts
import { Routes } from '@angular/router';
export const RECURRENCE_ROUTES: Routes = [
  { path: '', loadComponent: () => import('./recurrence-list/recurrence-list').then((m) => m.RecurrenceList) },
];
```

Registre na rota raiz: `{ path: 'recurrences', loadChildren: () => import('./features/recurrence/recurrence.routes').then((m) => m.RECURRENCE_ROUTES) }` e adicione o item no sidenav.

- [ ] **Step 3: Build**

Run: `cd frontend && npm run build`
Expected: OK; rota `recurrences` carrega.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/features/recurrence/ frontend/src/app/app.routes.ts
git commit -m "feat(recorrencia): adiciona feature Recorrências (CRUD no frontend)"
```

---

## Task 12: Sincronizar docs SDD

**Files:**
- Modify: `summary.md`, `domain.md`, `database-schema.md`

**Interfaces:** nenhuma.

- [ ] **Step 1: Atualizar `summary.md`**

Adicione uma seção "## Recorrência (`/api/recurrence-rules`)" descrevendo: regra como RRULE (subconjunto), projeção on-the-fly, `includeProjected`, confirmar/pular, EXDATE. Marque o que ficou para os sub-projetos #2/#3/#4.

- [ ] **Step 2: Atualizar `domain.md`**

Adicione `RecurrenceRule` e `RecurrenceException` ao diagrama de domínio e o enum `RecurrenceStatus : ACTIVE | CANCELLED`. Inclua os campos novos em `Transaction` (`recurrenceRule?`, `recurrenceOccurrence?`).

- [ ] **Step 3: Atualizar `database-schema.md`**

Adicione as linhas V19 (`recurrence_rules`, `recurrence_exceptions`, colunas em `transactions`) e V20 (seed dev recorrência) à tabela de migrations, e a constraint `UNIQUE(rule_id, occurrence_date)` + o índice único parcial de `transactions`.

- [ ] **Step 4: Commit**

```bash
git add summary.md domain.md database-schema.md
git commit -m "docs(recorrencia): sincroniza summary, domain e database-schema com o núcleo do motor"
```

---

## Verificação final (após todas as tasks)

- [ ] `cd backend && ./mvnw clean test` → tudo verde (inclui os testes de recorrência).
- [ ] `cd frontend && npm run build && npm test` → build OK, specs verdes.
- [ ] `GET /actuator/health` = UP com perfil dev (Flyway aplicou V19/V20).
- [ ] Smoke manual via `seed-dataset.http`: listar com `includeProjected=true` mostra a fantasma da Netflix; confirmar materializa; pular grava EXDATE e a fantasma some no mês.
- [ ] Indicar a branch (`feat/motor-recorrencia-nucleo`) e sugerir merge na `develop`.
