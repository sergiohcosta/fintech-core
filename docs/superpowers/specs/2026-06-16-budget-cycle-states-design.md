# Spec: Budget Cycle — Modelo de Estados OPEN / ENDED / CLOSED

**Data:** 2026-06-16
**Status:** Aprovado

---

## Contexto e Motivação

O modelo atual tem dois estados (`OPEN | CLOSED`) e permite abrir ciclos com datas futuras. Isso gerou:

- Bug: `remainingDays` calculado desde `today` mesmo para ciclos ainda não iniciados (ex: ciclo 01/08–31/08 aberto em 16/06 → 76 dias)
- Ausência de estado de "encerrado mas ainda ajustável" — o usuário não tem uma janela para acertar lançamentos antes do fechamento definitivo

---

## Modelo de Estados

| Estado | Condição | Transição | Mutável |
|--------|----------|-----------|---------|
| `OPEN` | `startDate ≤ today ≤ endDate` | usuário abre (validado) | sim |
| `ENDED` | `endDate < today`, ainda não fechado | automático, lazy | sim — ajustes finais |
| `CLOSED` | encerrado manualmente | usuário fecha um `ENDED` | não |

**Regra de abertura:** o backend valida que `startDate ≤ today ≤ endDate` ao criar o ciclo. Ciclos futuros são proibidos por construção — sem status `PLANNED`.

**Coexistência:** Um ciclo `ENDED` e um ciclo `OPEN` podem existir simultaneamente (o tenant fechou o ciclo anterior e abriu o novo antes de clicar em "Fechar definitivamente"). A constraint `UNIQUE(tenant_id) WHERE status='OPEN'` permanece — só um OPEN por vez.

---

## Transições de Estado

### OPEN → ENDED (lazy, automático)

Quando o backend recebe qualquer requisição referente a um ciclo com `status=OPEN` e `endDate < LocalDate.now()`, transita para `ENDED` e persiste antes de responder. Sem scheduler — a primeira requisição do dia seguinte ao fim do ciclo aciona a mudança.

Ponto de execução: método privado `checkAndTransitionToEnded(BudgetCycle)` chamado em `findCurrentByTenant()` e em `findByIdAndTenant()` — qualquer acesso a um ciclo verifica o estado.

```java
private BudgetCycle checkAndTransitionToEnded(BudgetCycle cycle) {
    if (cycle.getStatus() == OPEN && cycle.getEndDate().isBefore(LocalDate.now())) {
        cycle.setStatus(ENDED);
        cycleRepository.save(cycle);
        log.info("Ciclo transitado para ENDED [cycleId={}]", cycle.getId());
    }
    return cycle;
}
```

### ENDED → CLOSED (manual)

`POST /api/budget-cycles/{id}/close`

Validação: `status == ENDED` — retorna 422 se `OPEN` ("Ciclo ainda em andamento.") ou se já `CLOSED` ("Ciclo já está fechado.").

Ao fechar: calcula summary final, persiste nos campos `snapshot_*` da entidade, seta `status = CLOSED`.

### Abrir novo ciclo (manual, assistido)

`POST /api/budget-cycles` com validação `startDate ≤ today ≤ endDate`. O frontend guia o fluxo em dois passos: **preview → confirmação**.

---

## Endpoint de Preview

`GET /api/budget-cycles/preview?referenceMonth=YYYY-MM&startDay=N`

Retorna o que seria criado sem persistir nada. Reusa a lógica de `calculateCycleDates` e `calculateExpectedDate` já existente.

**Resposta (`BudgetCyclePreviewDTO`):**

```java
record BudgetCyclePreviewDTO(
    LocalDate startDate,
    LocalDate endDate,
    BigDecimal suggestedOpeningBalance,   // saldo líquido atual das contas
    List<RecurringItemPreviewDTO> recurringItems,
    List<InstallmentItemPreviewDTO> installmentItems,
    BigDecimal projectedIncome,           // soma dos recorrentes INCOME
    BigDecimal projectedExpense           // soma dos recorrentes EXPENSE + installments
)
```

```java
record RecurringItemPreviewDTO(String description, BigDecimal amount, String type, LocalDate expectedDate) {}
record InstallmentItemPreviewDTO(String description, BigDecimal amount, LocalDate expectedDate) {}
```

**Validação:** se `startDate > today || today > endDate` → 422 "As datas do ciclo devem compreender a data atual."

---

## Abertura com `openingBalance` Editável

`BudgetCycleOpenRequest` ganha campo opcional `openingBalance`:

```java
record BudgetCycleOpenRequest(
    @NotBlank String referenceMonth,    // YYYY-MM — continua sendo usado para calcular datas
    @NotNull @Min(1) @Max(28) Integer startDay,
    BigDecimal openingBalance           // nullable — se null, usa saldo atual das contas
) {}
```

Em `BudgetCycleService.open()`: se `req.openingBalance() != null`, usa o valor informado; caso contrário, mantém o cálculo automático via `accountRepository.sumLiquidBalanceByTenant(...)`.

---

## Summary — Campos por Estado

| Campo | OPEN | ENDED | CLOSED |
|-------|------|-------|--------|
| `plannedIncome/Expense` | calculado | calculado | snapshot |
| `realizedIncome/Expense` | calculado | calculado | snapshot |
| `projectedBalance` | calculado | calculado | snapshot |
| `availableToSpend` | calculado | calculado | snapshot |
| `unplannedExpenses` | calculado | calculado | snapshot |
| `remainingDays` | `DAYS.between(today, endDate)` | `null` | `null` |
| `dailyAllowance` | calculado | `null` | `null` |
| `pendingCount` | calculado | calculado | `0` (ciclo imutável) |

Para `CLOSED`, o `BudgetCycleResponseDTO` lê os campos `snapshot_*` da entidade em vez de chamar `calculateSummary`. Isso evita recalcular sobre dados que podem ter mudado após o fechamento. `pendingCount` retorna `0` — um ciclo fechado não tem itens pendentes por definição.

**Correção do bug:** `remainingDays` só existe para `OPEN`. Como `startDate ≤ today ≤ endDate` é garantido por construção, o valor é sempre `0 ≤ n ≤ duração_do_ciclo`. O caso de 76 dias deixa de existir.

---

## Endpoint GET /current

`GET /api/budget-cycles/current` passa a retornar, em ordem de prioridade:

1. Ciclo `OPEN` (se existir)
2. Ciclo `ENDED` (se existir — o tenant está no período de ajuste)
3. `404` — não há ciclo ativo; frontend exibe o botão de abertura

---

## Migration

**V14** (nova, acima de V13):

```sql
-- Atualiza constraint de status para incluir ENDED
ALTER TABLE budget_cycles
    DROP CONSTRAINT chk_cycle_status,
    ADD CONSTRAINT chk_cycle_status
        CHECK (status IN ('OPEN', 'ENDED', 'CLOSED'));
```

A constraint `uq_tenant_one_open_cycle` (`UNIQUE WHERE status='OPEN'`) permanece inalterada.

---

## Enum Java

```java
public enum BudgetCycleStatus {
    OPEN, ENDED, CLOSED
}
```

---

## Mudanças no Backend — Resumo de Arquivos

| Arquivo | Mudança |
|---------|---------|
| `V14__budget_cycle_ended_status.sql` | Criar — atualiza CHECK constraint |
| `BudgetCycleStatus.java` | Adicionar `ENDED` |
| `BudgetCycleOpenRequest.java` | Adicionar `openingBalance` nullable |
| `BudgetCyclePreviewDTO.java` | Criar |
| `RecurringItemPreviewDTO.java` | Criar |
| `InstallmentItemPreviewDTO.java` | Criar |
| `BudgetCycleService.java` | `findCurrentByTenant()` com lazy transition; `open()` com validação de data + openingBalance override; `close()` valida ENDED |
| `BudgetSummaryService.java` | `remainingDays`/`dailyAllowance` null quando não OPEN |
| `BudgetCycleResponseDTO.java` | Para CLOSED, lê snapshot em vez de recalcular |
| `BudgetCycleController.java` | `GET /current` usa `findCurrentByTenant`; novo `GET /preview` |
| `openapi.yaml` | Novo endpoint preview; `BudgetCycleStatus` com ENDED; `openingBalance` no request; `remainingDays`/`dailyAllowance` nullable |

---

## Frontend — Mudanças

### Estados visuais

| Status | UI |
|--------|-----|
| `OPEN` | Layout atual; card "Disponível" visível |
| `ENDED` | Banner "Período encerrado — revise os lançamentos e feche o ciclo quando estiver pronto" + botão "Fechar ciclo definitivamente"; card "Disponível" oculto; `remainingDays` oculto |
| `CLOSED` | Somente leitura, sem ações; exibe snapshot |
| Nenhum ciclo | Botão "Abrir novo ciclo" |

### Fluxo de abertura de ciclo (dois passos)

1. Usuário clica "Abrir novo ciclo"
2. Frontend chama `GET /preview?referenceMonth=YYYY-MM&startDay=N` (pré-preenchido com `budgetCycleStartDay` do tenant)
3. Dialog exibe: datas calculadas, `openingBalance` sugerido (editável), lista de itens recorrentes e parcelas que serão importados, totais projetados
4. Usuário confirma (ou ajusta `openingBalance`)
5. Frontend chama `POST /api/budget-cycles` com os dados confirmados

### Arquivos afetados

| Arquivo | Mudança |
|---------|---------|
| `openapi.yaml` + `npm run api:generate` | Regenerar cliente |
| `planning.service.ts` | Adicionar `previewCycle()` |
| `budget-cycle-current.ts` | Tratar status `ENDED` e `CLOSED`; novo método `openCycle()` com preview |
| `budget-cycle-current.html` | Banner ENDED; ocultar campos de daily allowance em ENDED |
| `budget-cycle-open-dialog/` | Novo componente — fluxo preview → confirmação |

---

## Testes

### Backend

- `BudgetCycleServiceTest`: lazy transition OPEN→ENDED; `open()` rejeita datas que não incluem hoje; `close()` aceita apenas ENDED; preview retorna itens esperados sem persistir
- `BudgetSummaryServiceTest`: `remainingDays` null em ENDED; `remainingDays` correto em OPEN (`≤ duração do ciclo`)
- `BudgetCycleControllerTest`: `GET /current` retorna ENDED quando não há OPEN; `GET /preview` retorna 422 para datas futuras

### Frontend

- Lógica pura de formatação de estado sem Angular (Vitest puro)
