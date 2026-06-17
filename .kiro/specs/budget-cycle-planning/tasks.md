# Implementation Plan

## Overview

Evolução do módulo de budget existente para suportar planejamento de ciclo orçamentário com cálculos de resumo (saldo projetado, disponível para gastar, mesada diária), realização de itens via transações, skip/unskip, snapshot de fechamento, reativação de recorrentes, e isolamento multi-tenant com 403 uniforme.

## Tasks

- [x] 1. Flyway migration V14 — adicionar colunas de snapshot e reference_month ao budget_cycles
  - Criar arquivo `backend/src/main/resources/db/migration/V14__budget_cycle_snapshot.sql`
  - Adicionar coluna `reference_month VARCHAR(7)` à tabela `budget_cycles`
  - Adicionar colunas: `snapshot_projected_balance NUMERIC(19,2)`, `snapshot_available_to_spend NUMERIC(19,2)`, `snapshot_realized_income NUMERIC(19,2)`, `snapshot_realized_expense NUMERIC(19,2)`, `snapshot_unplanned_expenses NUMERIC(19,2)` (todas nullable)
  - Preencher `reference_month` para ciclos existentes calculando a partir de `start_date` e `end_date`

- [x] 2. Evoluir entidade BudgetCycle — novos campos de snapshot e referenceMonth
  - Adicionar campo `referenceMonth` (String, `@Column(length = 7)`) à entidade `BudgetCycle`
  - Adicionar campos de snapshot: `snapshotProjectedBalance`, `snapshotAvailableToSpend`, `snapshotRealizedIncome`, `snapshotRealizedExpense`, `snapshotUnplannedExpenses` (todos BigDecimal, nullable)
  - Atualizar o builder no `BudgetCycleService.open()` para incluir `referenceMonth` a partir do request

- [x] 3. Criar BudgetSummaryService — cálculos de resumo do ciclo
  - Criar classe `BudgetSummaryService` em `com.fintech.api.service`
  - Implementar `calculateSummary(BudgetCycle cycle, List<BudgetItem> items)` — calcular todos os campos do resumo
  - Implementar `calculateDailyAllowance(BigDecimal availableToSpend, LocalDate endDate, LocalDate today)` — retornar zero se disponível ≤ 0 ou dias restantes ≤ 0, senão floor(disponível / diasRestantes, 2 casas)
  - Implementar `calculateUnplannedExpenses(BudgetCycle cycle)` — query no TransactionRepository
  - Adicionar query `sumUnplannedExpenses` no `TransactionRepository`
  - Excluir itens SKIPPED de todos os cálculos

- [x] 4. Evoluir BudgetCycleSummaryDTO — adicionar campos do design
  - Evoluir record adicionando: `openingBalance`, `unplannedExpenses`, `availableToSpend`, `dailyAllowance`, `remainingDays`
  - Remover campo `currentBalance` (substituído por `availableToSpend`)
  - Remover lógica de cálculo inline em `BudgetCycleResponseDTO.buildSummary()` — delegar para `BudgetSummaryService`

- [x] 5. Evoluir BudgetCycleResponseDTO — incluir referenceMonth e delegar resumo ao service
  - Adicionar campo `referenceMonth` ao record
  - Substituir método estático `fromEntity(cycle, items)` por construção que recebe `BudgetCycleSummaryDTO` já calculado
  - Atualizar `BudgetCycleController` para injetar `BudgetSummaryService` e calcular summary

- [x] 6. Evoluir BudgetCycleService.close() — persistir snapshot no fechamento
  - Ao fechar o ciclo, calcular resumo final via `BudgetSummaryService.calculateSummary()`
  - Persistir valores nas colunas de snapshot do ciclo
  - Para ciclos CLOSED na consulta de detalhes, retornar snapshot persistido ao invés de recalcular

- [x] 7. Evoluir BudgetItemService — adicionar validações de status e ciclo
  - No `update()`: rejeitar se status REALIZED ou SKIPPED ou ciclo CLOSED
  - No `delete()`: rejeitar se status REALIZED ou ciclo CLOSED; permitir PENDING e SKIPPED
  - No `create()`: rejeitar se ciclo CLOSED; validar expectedDate dentro do período do ciclo
  - Validar categoryId e accountId pertencem ao tenant

- [x] 8. Implementar realize/unrealize no BudgetItemService
  - Criar método `realize(BudgetItem, UUID transactionId, Tenant, User)`: validar ciclo OPEN + status PENDING, vincular/criar transação, atualizar amount, status → REALIZED
  - Validar transação: pertence ao tenant, não está vinculada a outro item, tipo compatível
  - Se transactionId null: criar nova transação com dados do item
  - Criar método `unrealize(BudgetItem)`: validar ciclo OPEN, desvincular, status → PENDING
  - Criar DTO `BudgetItemRealizeRequest` com campo `transactionId` (UUID nullable)

- [x] 9. Implementar skip/unskip no BudgetItemService
  - Criar método `skip(BudgetItem)`: validar ciclo OPEN + status PENDING, alterar para SKIPPED
  - Criar método `unskip(BudgetItem)`: validar ciclo OPEN + status SKIPPED, alterar para PENDING
  - Rejeitar skip em item REALIZED; rejeitar se ciclo CLOSED

- [x] 10. Evoluir BudgetItemController — novos endpoints realize, unrealize, skip, unskip
  - `POST /api/budget-items/{id}/realize` — aceita BudgetItemRealizeRequest
  - `DELETE /api/budget-items/{id}/realize` — unrealize
  - `POST /api/budget-items/{id}/skip` — skip
  - `DELETE /api/budget-items/{id}/skip` — unskip
  - Remover endpoints antigos `POST/DELETE /api/budget-items/{id}/link`

- [x] 11. Evoluir RecurringBudgetItemService — validações e reativação
  - No `update()`: rejeitar se `active == false`
  - Na criação/edição: validar category/account pertencem ao tenant
  - Criar método `reactivate(UUID id, Tenant)`: setar active=true
  - Evoluir listagem para aceitar filtro `Boolean activeFilter`

- [x] 12. Evoluir RecurringBudgetItemController — reactivate e filtro na listagem
  - `POST /api/recurring-budget-items/{id}/reactivate`
  - Query param `active` (Boolean opcional) no GET
  - Ordenar listagem por descrição crescente

- [x] 13. Evoluir RecurringBudgetItemRepository — queries com filtro
  - Adicionar `findAllByTenantOrderByDescriptionAsc(Tenant)`
  - Adicionar `findAllByTenantAndActiveOrderByDescriptionAsc(Tenant, boolean)`

- [x] 14. Garantir isolamento multi-tenant uniforme com 403
  - Alterar `BudgetItemService.findByIdAndTenant()` para lançar `AccessDeniedException` ao invés de `EntityNotFoundException`
  - Mesma alteração em `BudgetCycleService.findByIdAndTenant()` e `RecurringBudgetItemService.findByIdAndTenant()`
  - Verificar que GlobalExceptionHandler mapeia `AccessDeniedException` para 403

- [x] 15. Atualizar OpenAPI spec — novos endpoints e schemas
  - Adicionar schema `BudgetItemRealizeRequest`
  - Adicionar endpoints realize, unrealize, skip, unskip
  - Adicionar endpoint reactivate para recurring items
  - Evoluir schemas BudgetCycleResponse e BudgetCycleSummary
  - Remover endpoints antigos link/unlink
  - Adicionar importMappings no pom.xml

- [x] 16. Unit tests — BudgetSummaryService
  - Testar `calculateSummary()` com itens mistos (PENDING, REALIZED, SKIPPED)
  - Testar `calculateDailyAllowance()` — positivo, zero por disponível ≤ 0, zero por data expirada
  - Testar `calculateUnplannedExpenses()` — mock do repository

- [x] 17. Unit tests — BudgetItemService (realize, unrealize, skip, unskip, validações)
  - Realize com transactionId: vincula e atualiza amount
  - Realize sem transactionId: cria transação
  - Realize com transação de outro tenant: rejeita
  - Realize com transação já vinculada: rejeita
  - Realize com tipo incompatível: rejeita
  - Realize em item não-PENDING ou ciclo CLOSED: rejeita
  - Unrealize: remove vínculo e status → PENDING
  - Skip/unskip: transições válidas e rejeições

- [x] 18. Unit tests — BudgetCycleService (close com snapshot)
  - Close persiste snapshot com valores corretos
  - Close em ciclo já CLOSED: rejeita
  - Open com ciclo OPEN existente: rejeita
  - Open com overlap: rejeita

- [x] 19. Unit tests — RecurringBudgetItemService (validações, reativação)
  - Update em item inativo: rejeita
  - Reactivate restaura active=true
  - Create/update com categoria de outro tenant: rejeita

- [x] 20. Controller tests — segurança e validação HTTP
  - `GET /api/budget-cycles/{id}` com ID de outro tenant → 403
  - `GET /api/budget-cycles/{id}` com ID inexistente → 403
  - `POST /api/budget-cycles` sem auth → 401
  - `POST /api/budget-cycles` com referenceMonth inválido → 400
  - `POST /api/budget-items/{id}/realize` com transação de outro tenant → 403
  - `DELETE /api/budget-items/{id}` com item REALIZED → 422
  - `PUT /api/recurring-budget-items/{id}` com item inativo → 422
  - `POST /api/budget-items/{id}/skip` em ciclo CLOSED → 422

## Task Dependency Graph

```json
{
  "waves": [
    {"wave": 1, "tasks": [1]},
    {"wave": 2, "tasks": [2]},
    {"wave": 3, "tasks": [3, 13]},
    {"wave": 4, "tasks": [4, 7, 11]},
    {"wave": 5, "tasks": [5, 8, 9, 12, 14]},
    {"wave": 6, "tasks": [6, 10, 15]},
    {"wave": 7, "tasks": [16, 17, 18, 19]},
    {"wave": 8, "tasks": [20]}
  ]
}
```

## Notes

- jqwik não está no pom.xml atual. Testes de property-based (mencionados no design) podem ser adicionados como task futura se desejado — os unit tests cobrem a lógica.
- A migration V13 é apenas seed_dev.sql, então V14 é o próximo número de migration de schema.
- Os endpoints `link`/`unlink` existentes serão substituídos por `realize`/`unrealize` (breaking change na API — atualizar frontend junto).
- O `BudgetCycleService.open()` já implementa toda a lógica de criação; a evolução aqui é adicionar `referenceMonth` ao builder.
