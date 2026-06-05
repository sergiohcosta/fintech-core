# Dashboard Empty State + Posição Financeira

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Distinguir meses sem movimentação de meses com saldo zero real, exibindo sempre a posição financeira líquida (saldo total das contas) independente do período selecionado.

**Architecture:** O backend expõe dois novos campos no `DashboardSummaryDTO`: `transactionCount` (quantidade de transações não canceladas no período) e `totalAccountBalance` (saldo líquido acumulado de todas as contas `countInLiquidBalance = true`, sem filtro de período). O frontend usa `transactionCount > 0` para alternar entre os cards de receita/despesa e o empty state, enquanto o card de posição atual sempre exibe `totalAccountBalance`.

**Tech Stack:** Java 21, Spring Boot 4, JPA/JPQL, Angular 21 Zoneless Signals, Angular Material 3, OpenAPI spec-first, Orval (geração de cliente HTTP).

---

## Arquivos modificados

| Arquivo | Mudança |
|---|---|
| `api-spec/openapi.yaml` | Adiciona `transactionCount` e `totalAccountBalance` ao schema `DashboardSummaryDTO` |
| `backend/src/main/resources/static/openapi.yaml` | Idem (cópia servida pelo backend) |
| `backend/src/main/java/com/fintech/api/repository/TransactionRepository.java` | Dois novos métodos de query |
| `backend/src/main/java/com/fintech/api/dto/dashboard/DashboardSummaryDTO.java` | Dois novos campos; atualiza `of()` |
| `backend/src/main/java/com/fintech/api/service/DashboardService.java` | Chama os novos queries |
| `frontend/src/app/core/api/fintechSaaSAPI.schemas.ts` | Regenerado pelo Orval |
| `frontend/src/app/core/api/dashboard/dashboard.service.ts` | Regenerado pelo Orval |
| `frontend/src/app/features/dashboard/dashboard.ts` | Adiciona `hasTransactions` computed |
| `frontend/src/app/features/dashboard/dashboard.html` | Reestrutura template |
| `frontend/src/app/features/dashboard/dashboard.scss` | Estilos do empty state e posição |

**Criado:**
| Arquivo | Mudança |
|---|---|
| `backend/src/test/java/com/fintech/api/service/DashboardServiceTest.java` | Testes unitários do DashboardService |

---

## Task 1: Spec-first — atualiza OpenAPI

> Conceito: a spec é a fonte de verdade. Qualquer mudança de contrato começa aqui.
> Os dois arquivos são idênticos: `api-spec/openapi.yaml` é o spec de design; `backend/.../static/openapi.yaml` é o que o backend serve no `/openapi.yaml`.

**Files:**
- Modify: `api-spec/openapi.yaml` (linha ~291, bloco `DashboardSummaryDTO`)
- Modify: `backend/src/main/resources/static/openapi.yaml` (mesma mudança)

- [ ] **Substituir o bloco `DashboardSummaryDTO` em `api-spec/openapi.yaml`**

Localizar:
```yaml
    DashboardSummaryDTO:
      type: object
      properties:
        period:
          type: string
          pattern: '^\d{4}-\d{2}$'
          example: "2026-05"
        totalIncome:
          type: number
          format: double
        totalExpense:
          type: number
          format: double
        balance:
          type: number
          format: double
```

Substituir por:
```yaml
    DashboardSummaryDTO:
      type: object
      properties:
        period:
          type: string
          pattern: '^\d{4}-\d{2}$'
          example: "2026-05"
        totalIncome:
          type: number
          format: double
        totalExpense:
          type: number
          format: double
        balance:
          type: number
          format: double
        transactionCount:
          type: integer
          format: int64
          description: Quantidade de transações não canceladas no período. Zero indica mês sem movimentação.
        totalAccountBalance:
          type: number
          format: double
          description: Saldo líquido acumulado (todas as contas com countInLiquidBalance=true, sem filtro de período).
```

- [ ] **Aplicar a mesma mudança em `backend/src/main/resources/static/openapi.yaml`**

Mesmo bloco, mesma substituição.

- [ ] **Commit**

```bash
git add api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml
git commit -m "feat(openapi): adiciona transactionCount e totalAccountBalance ao DashboardSummaryDTO"
```

---

## Task 2: Backend — novos queries no `TransactionRepository`

> Conceito: JPQL opera sobre entidades e seus relacionamentos, não sobre tabelas SQL.
> `t.account.countInLiquidBalance` é possível porque `Transaction` tem `@ManyToOne account`.
> O CASE WHEN dentro do SUM é JPQL padrão — não é SQL nativo.

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/repository/TransactionRepository.java`

- [ ] **Adicionar os dois métodos ao final de `TransactionRepository`**

```java
// Conta transações não canceladas no período (independente de tipo).
// Retorna 0 quando o período não tem movimentação — usado para detectar empty state.
@Query("""
        SELECT COUNT(t)
        FROM Transaction t
        WHERE t.tenant = :tenant
          AND t.status <> :excluded
          AND t.date BETWEEN :start AND :end
        """)
long countByTenantAndPeriod(
        @Param("tenant") Tenant tenant,
        @Param("excluded") TransactionStatus excluded,
        @Param("start") LocalDate start,
        @Param("end") LocalDate end
);

// Saldo líquido acumulado: soma income e subtrai expense de TODAS as transações
// não canceladas, filtradas pelas contas marcadas como countInLiquidBalance.
// Sem filtro de período — representa a posição financeira atual.
@Query("""
        SELECT COALESCE(SUM(
            CASE WHEN t.type = :incomeType THEN t.amount ELSE -t.amount END
        ), 0)
        FROM Transaction t
        WHERE t.tenant = :tenant
          AND t.status <> :excluded
          AND t.account.countInLiquidBalance = true
        """)
BigDecimal sumNetLiquidBalanceByTenant(
        @Param("tenant") Tenant tenant,
        @Param("incomeType") TransactionType incomeType,
        @Param("excluded") TransactionStatus excluded
);
```

---

## Task 3: Backend — atualiza `DashboardSummaryDTO`

> Conceito: Java records são imutáveis — todos os campos ficam no construtor canônico.
> O factory `of()` é uma convenção para esconder a derivação de `balance = income - expense`
> e manter o construtor canônico limpo para o Jackson (desserialização).

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/dto/dashboard/DashboardSummaryDTO.java`

- [ ] **Substituir o conteúdo completo do arquivo**

```java
package com.fintech.api.dto.dashboard;

import java.math.BigDecimal;
import java.time.YearMonth;

public record DashboardSummaryDTO(
        YearMonth period,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal balance,
        long transactionCount,
        BigDecimal totalAccountBalance
) {
    public static DashboardSummaryDTO of(
            YearMonth period,
            BigDecimal income,
            BigDecimal expense,
            long transactionCount,
            BigDecimal totalAccountBalance) {
        return new DashboardSummaryDTO(
                period, income, expense, income.subtract(expense),
                transactionCount, totalAccountBalance);
    }
}
```

---

## Task 4: Backend TDD — `DashboardServiceTest` + atualiza `DashboardService`

> Conceito: escrevemos o teste ANTES do código de produção.
> Mockito intercepta as chamadas ao `transactionRepository` para que o teste
> seja unitário (sem banco de dados). `@InjectMocks` injeta os mocks no service via construtor (Lombok `@RequiredArgsConstructor`).

**Files:**
- Create: `backend/src/test/java/com/fintech/api/service/DashboardServiceTest.java`
- Modify: `backend/src/main/java/com/fintech/api/service/DashboardService.java`

- [ ] **Criar o teste com casos de empty month e month com dados**

`backend/src/test/java/com/fintech/api/service/DashboardServiceTest.java`:
```java
package com.fintech.api.service;

import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.dashboard.DashboardSummaryDTO;
import com.fintech.api.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.UUID;

import static com.fintech.api.domain.enums.TransactionType.EXPENSE;
import static com.fintech.api.domain.enums.TransactionType.INCOME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock TransactionRepository transactionRepository;
    @InjectMocks DashboardService service;

    private final YearMonth period = YearMonth.of(2026, 6);

    @Test
    @DisplayName("Mês sem movimentação: transactionCount=0 e totalAccountBalance reflete posição acumulada")
    void emptySummaryPreservesAccountBalance() {
        User user = buildUser();
        when(transactionRepository.sumByTenantAndTypeAndPeriod(any(), eq(INCOME), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(transactionRepository.sumByTenantAndTypeAndPeriod(any(), eq(EXPENSE), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(transactionRepository.countByTenantAndPeriod(any(), any(), any(), any()))
                .thenReturn(0L);
        when(transactionRepository.sumNetLiquidBalanceByTenant(any(), any(), any()))
                .thenReturn(new BigDecimal("3400.00"));

        DashboardSummaryDTO result = service.getSummary(period, user);

        assertThat(result.transactionCount()).isZero();
        assertThat(result.totalIncome()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalExpense()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.balance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalAccountBalance()).isEqualByComparingTo(new BigDecimal("3400.00"));
    }

    @Test
    @DisplayName("Mês com movimentação: balance = income - expense; posição acumulada independe do período")
    void summaryWithTransactionsComputesBalanceCorrectly() {
        User user = buildUser();
        when(transactionRepository.sumByTenantAndTypeAndPeriod(any(), eq(INCOME), any(), any(), any()))
                .thenReturn(new BigDecimal("5000.00"));
        when(transactionRepository.sumByTenantAndTypeAndPeriod(any(), eq(EXPENSE), any(), any(), any()))
                .thenReturn(new BigDecimal("1600.00"));
        when(transactionRepository.countByTenantAndPeriod(any(), any(), any(), any()))
                .thenReturn(5L);
        when(transactionRepository.sumNetLiquidBalanceByTenant(any(), any(), any()))
                .thenReturn(new BigDecimal("3400.00"));

        DashboardSummaryDTO result = service.getSummary(period, user);

        assertThat(result.transactionCount()).isEqualTo(5);
        assertThat(result.totalIncome()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(result.totalExpense()).isEqualByComparingTo(new BigDecimal("1600.00"));
        assertThat(result.balance()).isEqualByComparingTo(new BigDecimal("3400.00"));
        assertThat(result.totalAccountBalance()).isEqualByComparingTo(new BigDecimal("3400.00"));
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

- [ ] **Rodar os testes — devem falhar por `DashboardService` desatualizado**

```bash
cd backend && ./mvnw test -pl . -Dtest=DashboardServiceTest -q 2>&1 | tail -20
```

Esperado: `FAILED` — `DashboardService.getSummary()` ainda não chama os novos métodos.

- [ ] **Atualizar `DashboardService.getSummary()`**

Substituir o método completo:
```java
@Transactional(readOnly = true)
public DashboardSummaryDTO getSummary(YearMonth period, User user) {
    var tenant = user.getTenant();
    var start = period.atDay(1);
    var end = period.atEndOfMonth();

    BigDecimal income = transactionRepository.sumByTenantAndTypeAndPeriod(
            tenant, TransactionType.INCOME, TransactionStatus.CANCELLED, start, end);
    BigDecimal expense = transactionRepository.sumByTenantAndTypeAndPeriod(
            tenant, TransactionType.EXPENSE, TransactionStatus.CANCELLED, start, end);
    long count = transactionRepository.countByTenantAndPeriod(
            tenant, TransactionStatus.CANCELLED, start, end);
    BigDecimal totalAccountBalance = transactionRepository.sumNetLiquidBalanceByTenant(
            tenant, TransactionType.INCOME, TransactionStatus.CANCELLED);

    return DashboardSummaryDTO.of(period, income, expense, count, totalAccountBalance);
}
```

- [ ] **Rodar testes novamente — devem passar**

```bash
cd backend && ./mvnw test -pl . -Dtest=DashboardServiceTest -q 2>&1 | tail -10
```

Esperado: `BUILD SUCCESS` com 2 testes passando.

- [ ] **Rodar suite completa para garantir que nada quebrou**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -15
```

Esperado: `BUILD SUCCESS`.

- [ ] **Commit**

```bash
git add backend/src/main/java/com/fintech/api/repository/TransactionRepository.java \
        backend/src/main/java/com/fintech/api/dto/dashboard/DashboardSummaryDTO.java \
        backend/src/main/java/com/fintech/api/service/DashboardService.java \
        backend/src/test/java/com/fintech/api/service/DashboardServiceTest.java
git commit -m "feat(dashboard): adiciona transactionCount e totalAccountBalance ao resumo financeiro"
```

---

## Task 5: Frontend — regenera cliente Orval

> Conceito: Orval lê o `openapi.yaml` e gera `fintechSaaSAPI.schemas.ts` e os services.
> O arquivo `backend/src/main/resources/static/openapi.yaml` precisa estar idêntico ao `api-spec/openapi.yaml`
> pois o Orval aponta para o servidor local (`http://localhost:8080/openapi.yaml`).
> O backend deve estar rodando neste passo.

**Files:**
- Modify: `frontend/src/app/core/api/fintechSaaSAPI.schemas.ts` (gerado)
- Modify: `frontend/src/app/core/api/dashboard/dashboard.service.ts` (gerado)

- [ ] **Confirmar que o backend está rodando**

```bash
curl -s http://localhost:8080/actuator/health | grep '"status":"UP"'
```

Se não estiver: `cd backend && ./mvnw spring-boot:run &` e aguardar ~15s.

- [ ] **Regenerar o cliente**

```bash
cd frontend && npm run api:generate 2>&1 | tail -10
```

Esperado: sem erros; `fintechSaaSAPI.schemas.ts` e `dashboard.service.ts` atualizados.

- [ ] **Verificar que os novos campos aparecem no schema**

```bash
grep "transactionCount\|totalAccountBalance" frontend/src/app/core/api/fintechSaaSAPI.schemas.ts
```

Esperado:
```
  transactionCount?: number;
  totalAccountBalance?: number;
```

- [ ] **Commit**

```bash
git add frontend/src/app/core/api/
git commit -m "feat(frontend): regenera cliente Orval com transactionCount e totalAccountBalance"
```

---

## Task 6: Frontend — dashboard com posição atual e empty state

> Conceito: `computed()` em Angular Zoneless é uma derivação reativa — recalcula
> automaticamente quando `summary()` muda, sem necessidade de subscription ou `ngOnChanges`.
> O `@if / @else` no template Angular 17+ é o substituto do `*ngIf` — mais legível e com melhor tree-shaking.

**Files:**
- Modify: `frontend/src/app/features/dashboard/dashboard.ts`
- Modify: `frontend/src/app/features/dashboard/dashboard.html`
- Modify: `frontend/src/app/features/dashboard/dashboard.scss`

- [ ] **Adicionar `hasTransactions` em `dashboard.ts`**

Após a linha `summary = toSignal(this.summary$, { initialValue: null });`, adicionar:

```typescript
hasTransactions = computed(() => (this.summary()?.transactionCount ?? 0) > 0);
```

- [ ] **Substituir o bloco de summary cards em `dashboard.html`**

Substituir o bloco atual (linhas 18–60, do `@if (summary(); as s)` até o `@else` do spinner) por:

```html
  @if (summary(); as s) {

    <!-- Posição atual — exibida sempre, independente de haver movimentação no período -->
    <div class="position-wrapper">
      <mat-card class="summary-card position-card">
        <mat-card-content>
          <div class="card-icon">
            <mat-icon>account_balance</mat-icon>
          </div>
          <div class="card-info">
            <span class="card-label">Posição atual</span>
            <span class="card-value">{{ s.totalAccountBalance | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</span>
          </div>
        </mat-card-content>
      </mat-card>
    </div>

    <!-- Movimentações do período: cards quando há dados, empty state quando não há -->
    @if (hasTransactions()) {
      <div class="summary-cards">
        <mat-card class="summary-card income-card">
          <mat-card-content>
            <div class="card-icon">
              <mat-icon>trending_up</mat-icon>
            </div>
            <div class="card-info">
              <span class="card-label">Receita</span>
              <span class="card-value">{{ s.totalIncome | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</span>
            </div>
          </mat-card-content>
        </mat-card>

        <mat-card class="summary-card expense-card">
          <mat-card-content>
            <div class="card-icon">
              <mat-icon>trending_down</mat-icon>
            </div>
            <div class="card-info">
              <span class="card-label">Despesa</span>
              <span class="card-value">{{ s.totalExpense | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</span>
            </div>
          </mat-card-content>
        </mat-card>

        <mat-card class="summary-card" [class.balance-positive]="(s.balance ?? 0) >= 0" [class.balance-negative]="(s.balance ?? 0) < 0">
          <mat-card-content>
            <div class="card-icon">
              <mat-icon>account_balance_wallet</mat-icon>
            </div>
            <div class="card-info">
              <span class="card-label">Saldo do período</span>
              <span class="card-value">{{ s.balance | currency:'BRL':'symbol':'1.2-2':'pt-BR' }}</span>
            </div>
          </mat-card-content>
        </mat-card>
      </div>
    } @else {
      <div class="empty-state">
        <mat-icon class="empty-icon">inbox</mat-icon>
        <p class="empty-message">Nenhuma movimentação em {{ monthLabel() }}.</p>
        <a mat-stroked-button routerLink="/transactions">+ Lançar transação</a>
      </div>
    }

  } @else {
    <div class="loading-cards">
      <mat-spinner diameter="40" />
    </div>
  }
```

- [ ] **Adicionar estilos em `dashboard.scss`**

Adicionar ao final do arquivo:

```scss
.position-wrapper {
  margin-bottom: 16px;

  .position-card {
    background: var(--mat-sys-surface-variant);
  }
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 24px;
  gap: 12px;
  text-align: center;

  .empty-icon {
    font-size: 48px;
    width: 48px;
    height: 48px;
    color: var(--mat-sys-outline);
  }

  .empty-message {
    margin: 0;
    color: var(--mat-sys-on-surface-variant);
    font-size: 1rem;
  }
}
```

- [ ] **Commit**

```bash
git add frontend/src/app/features/dashboard/
git commit -m "feat(dashboard): exibe posição atual sempre e empty state em meses sem movimentação"
```

---

## Verificação final

- [ ] Subir o backend (`cd backend && ./mvnw spring-boot:run`)
- [ ] Subir o frontend (`cd frontend && npm start`)
- [ ] Acessar `http://localhost:4200` e navegar ao dashboard
- [ ] Confirmar: card "Posição atual" exibe o saldo acumulado correto
- [ ] Confirmar: mês atual (junho 2026, sem dados relevantes) exibe empty state + botão lançar transação
- [ ] Navegar para maio 2026: confirmar que os três cards de receita/despesa/saldo aparecem normalmente
- [ ] Criar uma transação em junho e confirmar que o empty state desaparece
