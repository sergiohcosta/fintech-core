# Budget Cycle Delete (CLOSED) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir que o ADMIN do tenant exclua ciclos com status `CLOSED`, removendo o ciclo e seus itens.

**Architecture:** Backend-first com TDD: `BudgetCycleService.delete()` exclui itens antes do ciclo (sem CASCADE no banco). O endpoint `DELETE /api/budget-cycles/{id}` é restrito a `ADMIN` via `SecurityConfigurations`. No frontend, `BudgetCycleList` exibe um botão de exclusão somente para ADMIN + CLOSED, com confirmação inline (sem novo componente).

**Tech Stack:** Java 21 / Spring Boot 4 / JUnit 5 + Mockito + AssertJ · Angular 21 Zoneless / Signals / Angular Material 3 / Orval

---

## Mapa de Arquivos

| Arquivo | Operação |
|---------|----------|
| `backend/src/main/java/com/fintech/api/service/BudgetCycleService.java` | Modificar — adicionar `delete()` |
| `backend/src/test/java/com/fintech/api/service/BudgetCycleServiceTest.java` | Modificar — testes de delete |
| `backend/src/main/java/com/fintech/api/controller/BudgetCycleController.java` | Modificar — endpoint `DELETE /{id}` |
| `backend/src/main/java/com/fintech/api/config/SecurityConfigurations.java` | Modificar — regra ADMIN para DELETE |
| `api-spec/openapi.yaml` | Modificar — schema do endpoint delete |
| `backend/src/main/resources/static/openapi.yaml` | Copiar da api-spec |
| `frontend/src/app/core/api/` | Regenerar via Orval |
| `frontend/src/app/features/planning/planning.service.ts` | Modificar — `deleteCycle()` |
| `frontend/src/app/features/planning/budget-cycle-list/budget-cycle-list.ts` | Modificar — AuthService, estado de confirmação, método delete |
| `frontend/src/app/features/planning/budget-cycle-list/budget-cycle-list.html` | Modificar — botão delete + confirmação inline |

---

## Task 1: BudgetCycleService.delete() — TDD

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/BudgetCycleService.java`
- Modify: `backend/src/test/java/com/fintech/api/service/BudgetCycleServiceTest.java`

**Context:** `budget_items` tem FK `cycle_id NOT NULL REFERENCES budget_cycles(id)` SEM CASCADE — os itens precisam ser excluídos antes do ciclo. `itemRepository.findAllByCycleWithDetails(cycle)` retorna todos os itens. `itemRepository.deleteAll(list)` remove todos.

- [ ] **Step 1: Escrever os testes**

READ `backend/src/test/java/com/fintech/api/service/BudgetCycleServiceTest.java` para ver os helpers existentes (`tenantWith()`, `createCycle()`, mocks disponíveis). Adicionar no final da classe:

```java
// ---- delete() ----

@Test
@DisplayName("delete() em ciclo CLOSED remove itens e ciclo")
void delete_cicloFechado_removeItemsECiclo() {
    Tenant tenant = tenantWith(1);
    BudgetCycle cycle = BudgetCycle.builder()
        .id(UUID.randomUUID()).tenant(tenant)
        .startDate(LocalDate.of(2026, 5, 1))
        .endDate(LocalDate.of(2026, 5, 31))
        .openingBalance(BigDecimal.ZERO)
        .status(BudgetCycleStatus.CLOSED)
        .build();

    List<BudgetItem> items = List.of(
        BudgetItem.builder().id(UUID.randomUUID()).cycle(cycle).build()
    );

    when(cycleRepository.findById(cycle.getId())).thenReturn(Optional.of(cycle));
    when(itemRepository.findAllByCycleWithDetails(cycle)).thenReturn(items);

    service.delete(cycle.getId(), tenant);

    verify(itemRepository).deleteAll(items);
    verify(cycleRepository).delete(cycle);
}

@Test
@DisplayName("delete() em ciclo não-CLOSED lança IllegalStateException")
void delete_cicloNaoFechado_lancaException() {
    Tenant tenant = tenantWith(1);
    BudgetCycle cycle = BudgetCycle.builder()
        .id(UUID.randomUUID()).tenant(tenant)
        .startDate(LocalDate.of(2026, 6, 1))
        .endDate(LocalDate.of(2026, 6, 30))
        .openingBalance(BigDecimal.ZERO)
        .status(BudgetCycleStatus.ENDED)
        .build();

    when(cycleRepository.findById(cycle.getId())).thenReturn(Optional.of(cycle));

    assertThatThrownBy(() -> service.delete(cycle.getId(), tenant))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Apenas ciclos fechados podem ser excluídos.");
}
```

- [ ] **Step 2: Confirmar que os testes falham**

```bash
cd backend && ./mvnw test -Dtest=BudgetCycleServiceTest -q 2>&1 | grep -E "FAIL|ERROR|Tests run"
```

Esperado: falha de compilação (método `delete` não existe).

- [ ] **Step 3: Implementar `delete()` no BudgetCycleService**

Adicionar após o método `close()`:

```java
@Transactional
public void delete(UUID cycleId, Tenant tenant) {
    BudgetCycle cycle = cycleRepository.findById(cycleId)
        .filter(c -> c.getTenant().getId().equals(tenant.getId()))
        .orElseThrow(() -> new AccessDeniedException("Acesso negado."));

    if (cycle.getStatus() != BudgetCycleStatus.CLOSED) {
        throw new IllegalStateException("Apenas ciclos fechados podem ser excluídos.");
    }

    itemRepository.deleteAll(itemRepository.findAllByCycleWithDetails(cycle));
    cycleRepository.delete(cycle);
    log.info("Ciclo excluído [cycleId={} tenantId={}]", cycleId, tenant.getId());
}
```

- [ ] **Step 4: Confirmar que os testes passam**

```bash
cd backend && ./mvnw test -Dtest=BudgetCycleServiceTest -q 2>&1 | grep -E "BUILD|Tests run"
```

Esperado: `BUILD SUCCESS`, sem falhas.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/BudgetCycleService.java \
        backend/src/test/java/com/fintech/api/service/BudgetCycleServiceTest.java
git commit -m "feat(planning): exclusão de ciclo CLOSED por ADMIN"
```

---

## Task 2: Controller + Security

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/controller/BudgetCycleController.java`
- Modify: `backend/src/main/java/com/fintech/api/config/SecurityConfigurations.java`

- [ ] **Step 1: Adicionar endpoint DELETE ao controller**

READ `backend/src/main/java/com/fintech/api/controller/BudgetCycleController.java`. Adicionar após o método `syncInstallments()`:

```java
@DeleteMapping("/{id}")
public ResponseEntity<Void> delete(@PathVariable UUID id) {
    User user = getUser();
    cycleService.delete(id, user.getTenant());
    return ResponseEntity.noContent().build();
}
```

- [ ] **Step 2: Adicionar regra ADMIN no SecurityConfigurations**

READ `backend/src/main/java/com/fintech/api/config/SecurityConfigurations.java`. Na cadeia de `authorizeHttpRequests`, após a linha do `PATCH /api/tenant/settings`, adicionar:

```java
.requestMatchers(HttpMethod.DELETE, "/api/budget-cycles/*").hasRole("ADMIN")
```

O import necessário (`HttpMethod`) já deve estar presente.

- [ ] **Step 3: Rodar todos os testes do backend**

```bash
cd backend && ./mvnw test -q 2>&1 | grep -E "BUILD|Tests run:|FAIL"
```

Esperado: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/fintech/api/controller/BudgetCycleController.java \
        backend/src/main/java/com/fintech/api/config/SecurityConfigurations.java
git commit -m "feat(planning): endpoint DELETE /api/budget-cycles/{id} restrito a ADMIN"
```

---

## Task 3: OpenAPI spec + regenerar Orval

**Files:**
- Modify: `api-spec/openapi.yaml`
- Modify: `backend/src/main/resources/static/openapi.yaml`
- Modify: `frontend/src/app/core/api/` (regenerar)

- [ ] **Step 1: Adicionar operação DELETE ao path `/api/budget-cycles/{id}`**

READ `api-spec/openapi.yaml` e localize o bloco `  /api/budget-cycles/{id}:` (que tem as operações GET e POST/close existentes). Adicionar a operação `delete` no mesmo bloco:

```yaml
    delete:
      tags: [budget]
      operationId: deleteBudgetCycle
      summary: Exclui um ciclo fechado (ADMIN)
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '204':
          description: Ciclo excluído
        '403':
          description: Acesso negado (não é ADMIN ou ciclo não pertence ao tenant)
        '422':
          description: Ciclo não está com status CLOSED
      security:
        - bearerAuth: []
```

- [ ] **Step 2: Copiar spec para o backend**

```bash
cp api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml
```

- [ ] **Step 3: Regenerar cliente Orval**

```bash
cd frontend && npm run api:generate 2>&1 | tail -5
```

Esperado: mensagem de sucesso do Orval.

- [ ] **Step 4: Verificar TypeScript**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

Esperado: zero erros.

- [ ] **Step 5: Commit**

```bash
cd ..
git add api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml frontend/src/app/core/api/
git commit -m "feat(planning): adiciona DELETE /api/budget-cycles/{id} na spec OpenAPI"
```

---

## Task 4: Frontend — BudgetCycleList com exclusão inline

**Files:**
- Modify: `frontend/src/app/features/planning/planning.service.ts`
- Modify: `frontend/src/app/features/planning/budget-cycle-list/budget-cycle-list.ts`
- Modify: `frontend/src/app/features/planning/budget-cycle-list/budget-cycle-list.html`

**Context:**
- `AuthService` está em `frontend/src/app/core/services/auth.ts` com `isAdmin = computed(() => currentUser()?.role === 'ADMIN')`
- `BudgetCycleList` já injeta `PlanningService` e `MatSnackBar`
- O HTML usa `mat-table` com coluna `actions`; a coluna `status` exibe `chip` com texto do status
- O status `ENDED` não está tratado na coluna chip — corrigir junto (chip exibe "Pago" para ENDED, bug)

- [ ] **Step 1: Adicionar `deleteCycle()` ao PlanningService**

READ `frontend/src/app/features/planning/planning.service.ts`. Adicionar após `listCycles()`:

```ts
deleteCycle(id: string): Observable<void> {
  return this.budget.deleteBudgetCycle(id);
}
```

Não é necessário importar nada novo — `Observable` já está importado e o método `deleteBudgetCycle` vem do cliente Orval gerado em Task 3.

- [ ] **Step 2: Atualizar BudgetCycleList**

Substituir o conteúdo de `frontend/src/app/features/planning/budget-cycle-list/budget-cycle-list.ts` por:

```ts
import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { finalize } from 'rxjs/operators';
import { PlanningService } from '../planning.service';
import { AuthService } from '../../../core/services/auth';
import { BudgetCycleResponse } from '../../../core/api/fintechSaaSAPI.schemas';

@Component({
  selector: 'app-budget-cycle-list',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe, RouterLink,
    MatButtonModule, MatChipsModule, MatIconModule, MatSnackBarModule, MatTableModule,
  ],
  templateUrl: './budget-cycle-list.html',
})
export class BudgetCycleList implements OnInit {
  private readonly planningService = inject(PlanningService);
  private readonly authService = inject(AuthService);
  private readonly snackBar = inject(MatSnackBar);

  readonly cycles = signal<BudgetCycleResponse[]>([]);
  readonly loading = signal(true);
  readonly deleting = signal<string | null>(null);     // id do ciclo aguardando confirmação
  readonly isAdmin = this.authService.isAdmin;

  displayedColumns = ['period', 'openingBalance', 'status', 'actions'];

  ngOnInit(): void {
    this.planningService.listCycles()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: page => this.cycles.set(page.content ?? []),
        error: () => this.snackBar.open('Erro ao carregar ciclos.', 'OK', { duration: 3000 }),
      });
  }

  requestDelete(id: string): void {
    this.deleting.set(id);
  }

  cancelDelete(): void {
    this.deleting.set(null);
  }

  confirmDelete(id: string): void {
    this.planningService.deleteCycle(id).subscribe({
      next: () => {
        this.cycles.update(cs => cs.filter(c => c.id !== id));
        this.deleting.set(null);
        this.snackBar.open('Ciclo excluído.', 'OK', { duration: 2000 });
      },
      error: () => {
        this.deleting.set(null);
        this.snackBar.open('Erro ao excluir ciclo.', 'OK', { duration: 3000 });
      },
    });
  }
}
```

- [ ] **Step 3: Atualizar o HTML**

Substituir o conteúdo de `frontend/src/app/features/planning/budget-cycle-list/budget-cycle-list.html` por:

```html
<div style="padding:24px;max-width:800px;margin:0 auto">
  <div style="margin-bottom:16px">
    <h1 style="margin:0">Histórico de ciclos</h1>
  </div>

  @if (loading()) {
    <p>Carregando...</p>
  } @else if (cycles().length === 0) {
    <p>Nenhum ciclo encontrado.</p>
  } @else {
    <table mat-table [dataSource]="cycles()" style="width:100%">
      <ng-container matColumnDef="period">
        <th mat-header-cell *matHeaderCellDef>Período</th>
        <td mat-cell *matCellDef="let c">
          {{ c.startDate | date:'dd/MM/yyyy' }} – {{ c.endDate | date:'dd/MM/yyyy' }}
        </td>
      </ng-container>
      <ng-container matColumnDef="openingBalance">
        <th mat-header-cell *matHeaderCellDef>Saldo inicial</th>
        <td mat-cell *matCellDef="let c">{{ c.openingBalance | currency:'BRL' }}</td>
      </ng-container>
      <ng-container matColumnDef="status">
        <th mat-header-cell *matHeaderCellDef>Status</th>
        <td mat-cell *matCellDef="let c">
          <mat-chip>
            {{ c.status === 'OPEN' ? 'Aberto' : c.status === 'ENDED' ? 'Encerrado' : 'Fechado' }}
          </mat-chip>
        </td>
      </ng-container>
      <ng-container matColumnDef="actions">
        <th mat-header-cell *matHeaderCellDef></th>
        <td mat-cell *matCellDef="let c">
          @if (deleting() === c.id) {
            <span style="font-size:0.85rem;margin-right:8px">Excluir?</span>
            <button mat-button color="warn" (click)="confirmDelete(c.id!)">Sim</button>
            <button mat-button (click)="cancelDelete()">Não</button>
          } @else {
            <a mat-icon-button [routerLink]="['/planning/cycles', c.id]">
              <mat-icon>visibility</mat-icon>
            </a>
            @if (isAdmin() && c.status === 'CLOSED') {
              <button mat-icon-button color="warn" (click)="requestDelete(c.id!)"
                      aria-label="Excluir ciclo">
                <mat-icon>delete_outline</mat-icon>
              </button>
            }
          }
        </td>
      </ng-container>
      <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
      <tr mat-row *matRowDef="let row; columns: displayedColumns"></tr>
    </table>
  }
</div>
```

- [ ] **Step 4: Verificar TypeScript**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

Esperado: zero erros.

- [ ] **Step 5: Commit**

```bash
cd ..
git add frontend/src/app/features/planning/planning.service.ts \
        frontend/src/app/features/planning/budget-cycle-list/
git commit -m "feat(planning): exclusão de ciclo CLOSED na tela de histórico (só ADMIN)"
```

---

## Task 5: Verificação final

- [ ] **Step 1: Rodar todos os testes do backend**

```bash
cd backend && ./mvnw test -q 2>&1 | grep -E "BUILD|Tests run:|FAIL"
```

Esperado: `BUILD SUCCESS`, zero falhas.

- [ ] **Step 2: Build Angular**

```bash
cd frontend && npm run build 2>&1 | tail -5
```

Esperado: `Application bundle generation complete.`

- [ ] **Step 3: Commit de ajustes se necessário**

```bash
git add -p
git commit -m "fix(planning): ajustes pós-verificação da exclusão de ciclo"
```
