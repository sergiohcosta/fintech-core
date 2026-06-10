# Mapeamento de Taxonomia — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Criar tela dedicada (`/categories/taxonomy`) que permite ao ADMIN do tenant mapear manualmente categorias raiz existentes aos `taxonomy_code` padrão, resolving o gap de tenants criados antes da feature de seed automático (issue #71).

**Architecture:** Backend estende `CategoryCreateDTO` (o DTO realmente usado por `update()`) com campo opcional `taxonomyCode`, guarda ADMIN via service layer. Frontend cria `TaxonomyMappingComponent` com dois painéis lado a lado — categorias do tenant à esquerda, taxonomia padrão à direita — com sugestão automática client-side (`taxonomy-suggestions.ts`) e persistência via `forkJoin` de chamadas `PUT /api/categories/{id}`.

**Tech Stack:** Java 21 / Spring Boot 4 / JPA, Angular 21 Zoneless / Signals / Angular Material 3, OpenAPI spec-first + Orval para codegen TypeScript

---

### Contexto crítico de implementação

**Por que `CategoryCreateDTO` e não `CategoryUpdateDTO`:**
`CategoryUpdateDTO.java` existe em `src/main/java/com/fintech/api/dto/category/` mas **não é usado** pelo endpoint PUT. O `CategoryController` e `CategoryService.update()` recebem `CategoryCreateDTO`. Por isso `taxonomyCode` vai em `CategoryCreateDTO` — tanto no Java quanto no schema `openapi.yaml`.

**Orval e modelos gerados:**
O frontend não tem modelos separados por arquivo — tudo está em `frontend/src/app/core/api/fintechSaaSAPI.schemas.ts`. Rodar `npm run api:generate` atualiza esse arquivo. Depois do Task 2, `CategoryCreateDTO` e `CategoryResponseDTO` já terão `taxonomyCode`.

**`CategoryResponseDTO.taxonomyCode` já está no openapi.yaml** (adicionado no Task da issue #71) mas o Orval ainda não foi rodado — o campo não aparece na interface TypeScript. O Task 2 resolve isso.

---

### Task 1: Backend — `taxonomyCode` em `CategoryCreateDTO` + ADMIN guard em `CategoryService.update()`

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/dto/category/CategoryCreateDTO.java`
- Modify: `backend/src/main/java/com/fintech/api/service/CategoryService.java`
- Modify: `backend/src/test/java/com/fintech/api/service/CategoryServiceTest.java`

**Conceitos pedagógicos:**
- Java records têm um construtor canônico com todos os campos. Adicionar um campo novo expande esse construtor — todos os call sites `new CategoryCreateDTO(...)` com 4 argumentos precisam de um 5º `null`.
- `UserRole` fica em `com.fintech.api.domain.enums.UserRole`.
- `AccessDeniedException` fica em `org.springframework.security.access.AccessDeniedException`. O `ExceptionTranslationFilter` do Spring Security já a intercepta e retorna 403 automaticamente — sem precisar adicionar handler no `GlobalExceptionHandler`.
- Semântica "ausente = não alterar": o frontend de edição de categoria nunca envia `taxonomyCode`, então Jackson o preenche com `null` → o valor existente é preservado automaticamente. Essa é uma convenção do projeto, não uma feature do Jackson.

- [ ] **Step 1: Adicionar `taxonomyCode` ao record `CategoryCreateDTO`**

```java
// backend/src/main/java/com/fintech/api/dto/category/CategoryCreateDTO.java
package com.fintech.api.dto.category;

import java.util.UUID;

public record CategoryCreateDTO(
        String name,
        String icon,
        String color,
        UUID parentId,
        String taxonomyCode  // nullable — null = não alterar o valor existente
) {
}
```

- [ ] **Step 2: Corrigir os 4 call sites existentes em `CategoryServiceTest` (acrescentar `null` 5º arg)**

Em `backend/src/test/java/com/fintech/api/service/CategoryServiceTest.java`:

Linha 45 → `CategoryCreateDTO dto = new CategoryCreateDTO("Pai", "home", "#00ff00", null, null);`

Linha 67 → `CategoryCreateDTO dto = new CategoryCreateDTO("Pai", "star", "#abcdef", null, null);`

Linha 90 → `CategoryCreateDTO dto = new CategoryCreateDTO("Pai Renomeado", "folder", "#3f51b5", null, null);`

Linha 107 → `CategoryCreateDTO dto = new CategoryCreateDTO("Folha", "home", "#ffffff", null, null);`

- [ ] **Step 3: Escrever 3 novos testes (vão falhar — TDD)**

Adicionar ao final de `CategoryServiceTest.java`, antes dos helpers `buildUser()` e `buildCategory()`:

```java
// ─── update: taxonomyCode ────────────────────────────────────────────────────

@Test
@DisplayName("update persiste taxonomyCode quando usuário é ADMIN")
void update_persistsTaxonomyCodeForAdmin() {
    User admin = buildAdmin();
    Category category = buildCategory("Moradia", "home", "#3f51b5");
    when(repository.findByIdAndTenantIdAndDeletedAtIsNull(category.getId(), admin.getTenant().getId()))
            .thenReturn(Optional.of(category));
    when(repository.save(any())).thenReturn(category);

    CategoryCreateDTO dto = new CategoryCreateDTO("Moradia", "home", "#3f51b5", null, "HOUSING");
    service.update(category.getId(), dto, admin);

    assertThat(category.getTaxonomyCode()).isEqualTo("HOUSING");
}

@Test
@DisplayName("update lança AccessDeniedException quando não-ADMIN tenta definir taxonomyCode")
void update_throwsAccessDeniedForNonAdmin() {
    User user = buildUser();
    Category category = buildCategory("Moradia", "home", "#3f51b5");
    when(repository.findByIdAndTenantIdAndDeletedAtIsNull(category.getId(), user.getTenant().getId()))
            .thenReturn(Optional.of(category));

    CategoryCreateDTO dto = new CategoryCreateDTO("Moradia", "home", "#3f51b5", null, "HOUSING");

    assertThatThrownBy(() -> service.update(category.getId(), dto, user))
            .isInstanceOf(AccessDeniedException.class);
}

@Test
@DisplayName("update NÃO altera taxonomyCode quando dto.taxonomyCode() é null")
void update_doesNotAlterTaxonomyCodeWhenNull() {
    User admin = buildAdmin();
    Category category = buildCategory("Moradia", "home", "#3f51b5");
    category.setTaxonomyCode("HOUSING");
    when(repository.findByIdAndTenantIdAndDeletedAtIsNull(category.getId(), admin.getTenant().getId()))
            .thenReturn(Optional.of(category));
    when(repository.save(any())).thenReturn(category);

    CategoryCreateDTO dto = new CategoryCreateDTO("Moradia Renomeada", "home", "#3f51b5", null, null);
    service.update(category.getId(), dto, admin);

    assertThat(category.getTaxonomyCode()).isEqualTo("HOUSING");
}
```

Adicionar `buildAdmin()` no bloco de helpers:

```java
private User buildAdmin() {
    Tenant tenant = new Tenant();
    tenant.setId(UUID.randomUUID());
    User admin = new User();
    admin.setTenant(tenant);
    admin.setRole(UserRole.ADMIN);
    return admin;
}
```

Adicionar imports no topo do arquivo:

```java
import com.fintech.api.domain.enums.UserRole;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

- [ ] **Step 4: Rodar testes para verificar que os 3 novos falham**

```bash
cd /home/sergio/fintech-core/backend && ./mvnw test -Dtest=CategoryServiceTest -q 2>&1 | tail -20
```

Resultado esperado: os 3 novos testes falham (método `update()` não trata `taxonomyCode` ainda); os 4 antigos passam.

- [ ] **Step 5: Implementar o ADMIN guard em `CategoryService.update()`**

Adicionar imports em `backend/src/main/java/com/fintech/api/service/CategoryService.java`:

```java
import com.fintech.api.domain.enums.UserRole;
import org.springframework.security.access.AccessDeniedException;
```

No método `update()`, após `category.setColor(dto.color());` e antes do bloco `if (dto.parentId() != null)`:

```java
if (dto.taxonomyCode() != null) {
    if (user.getRole() != UserRole.ADMIN) {
        throw new AccessDeniedException("Somente ADMIN pode definir taxonomy_code");
    }
    category.setTaxonomyCode(dto.taxonomyCode());
}
```

- [ ] **Step 6: Rodar testes para confirmar que todos passam**

```bash
cd /home/sergio/fintech-core/backend && ./mvnw test -Dtest=CategoryServiceTest -q 2>&1 | tail -10
```

Resultado esperado: `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 7: Rodar a suite completa para verificar sem regressões**

```bash
cd /home/sergio/fintech-core/backend && ./mvnw test -q 2>&1 | tail -10
```

Resultado esperado: `BUILD SUCCESS`

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/fintech/api/dto/category/CategoryCreateDTO.java \
        backend/src/main/java/com/fintech/api/service/CategoryService.java \
        backend/src/test/java/com/fintech/api/service/CategoryServiceTest.java
git commit -m "feat(category): adiciona taxonomyCode ao CategoryCreateDTO com guard ADMIN no update"
```

---

### Task 2: openapi.yaml + regeneração Orval

**Files:**
- Modify: `api-spec/openapi.yaml`
- Modify (gerado): `frontend/src/app/core/api/fintechSaaSAPI.schemas.ts`

**Conceitos pedagógicos:**
- O backend usa `openapi-generator-maven-plugin` com `generateModels=false` — gera apenas interfaces de controller, não os DTOs Java. Os DTOs são manuscritos e precisam ficar consistentes com o spec. Atualizar o YAML não afeta compilação do backend.
- O Orval lê `api-spec/openapi.yaml` e regenera `fintechSaaSAPI.schemas.ts` e os `*.service.ts`. Rodar `npm run api:generate` (no diretório `frontend/`) recria esses arquivos do zero.
- `CategoryResponseDTO.taxonomyCode` já está no openapi.yaml (adicionado na issue #71) mas o campo não está no TypeScript gerado porque o Orval não foi rodado desde então. Este task corrige isso ao mesmo tempo que adiciona `taxonomyCode` ao `CategoryCreateDTO`.

- [ ] **Step 1: Adicionar `taxonomyCode` ao schema `CategoryCreateDTO` no openapi.yaml**

Em `api-spec/openapi.yaml`, localizar o schema `CategoryCreateDTO` (próximo da linha 86). Adicionar o campo após `parentId`:

```yaml
    CategoryCreateDTO:
      type: object
      required: [name, icon, color]
      properties:
        name:
          type: string
        icon:
          type: string
        color:
          type: string
        parentId:
          type: string
          format: uuid
          nullable: true
        taxonomyCode:
          type: string
          nullable: true
          description: "Código de taxonomia padrão. null = não alterar valor existente."
```

- [ ] **Step 2: Rodar Orval para regenerar os modelos TypeScript**

```bash
cd /home/sergio/fintech-core/frontend && npm run api:generate 2>&1 | tail -10
```

Resultado esperado: saída sem erros, arquivos em `src/app/core/api/` atualizados.

- [ ] **Step 3: Verificar que ambos os campos aparecem no modelo gerado**

```bash
grep -n "taxonomyCode" /home/sergio/fintech-core/frontend/src/app/core/api/fintechSaaSAPI.schemas.ts
```

Resultado esperado: pelo menos 2 linhas — uma em `CategoryCreateDTO` (como `taxonomyCode?: string | null`) e outra em `CategoryResponseDTO`.

- [ ] **Step 4: Commit**

```bash
git add api-spec/openapi.yaml \
        frontend/src/app/core/api/
git commit -m "feat(openapi): adiciona taxonomyCode ao schema CategoryCreateDTO e regenera modelos Orval"
```

---

### Task 3: Frontend — `taxonomy-suggestions.ts` com testes

**Files:**
- Create: `frontend/src/app/features/category/taxonomy-mapping/taxonomy-suggestions.ts`
- Create: `frontend/src/app/features/category/taxonomy-mapping/taxonomy-suggestions.spec.ts`

**Conceitos pedagógicos:**
- Este arquivo é lógica pura: sem Angular, sem DI, sem signals. Apenas constantes e funções. Isso torna os testes triviais — sem TestBed, sem mocks, sem boilerplate Angular.
- A normalização `normalize('NFD').replace(/[̀-ͯ]/g, '')` remove acentos de forma portável. `NFD` decompõe caracteres compostos (`ã` vira `a` + combining tilde); o replace remove os combining diacritics (range Unicode U+0300–U+036F). Resultado: `'Alimentação'` → `'alimentacao'`.
- O `getSuggestion()` retorna `null` (não `undefined`) por convenção: o tipo de retorno é `string | null`, o que é mais fácil de usar com `@if (suggestedCode())` nos templates Angular.

- [ ] **Step 1: Escrever o teste antes da implementação**

Criar `frontend/src/app/features/category/taxonomy-mapping/taxonomy-suggestions.spec.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { getSuggestion, TAXONOMY_ROOTS } from './taxonomy-suggestions';

describe('getSuggestion', () => {
  it('retorna código para nome exato em PT-BR', () => {
    expect(getSuggestion('Moradia')).toBe('HOUSING');
    expect(getSuggestion('Alimentação')).toBe('FOOD');
    expect(getSuggestion('Transporte')).toBe('TRANSPORT');
    expect(getSuggestion('Pets')).toBe('PETS');
  });

  it('é case-insensitive', () => {
    expect(getSuggestion('MORADIA')).toBe('HOUSING');
    expect(getSuggestion('moradia')).toBe('HOUSING');
    expect(getSuggestion('Moradia')).toBe('HOUSING');
  });

  it('ignora acentos ao comparar', () => {
    expect(getSuggestion('Saude')).toBe('HEALTH');
    expect(getSuggestion('Saúde')).toBe('HEALTH');
    expect(getSuggestion('Educacao')).toBe('EDUCATION');
    expect(getSuggestion('Educação')).toBe('EDUCATION');
    expect(getSuggestion('Financas')).toBe('FINANCIAL');
    expect(getSuggestion('Finanças')).toBe('FINANCIAL');
  });

  it('retorna null para nome sem correspondência', () => {
    expect(getSuggestion('Roupas & Casa')).toBeNull();
    expect(getSuggestion('Desconhecido')).toBeNull();
  });

  it('retorna null para string vazia', () => {
    expect(getSuggestion('')).toBeNull();
  });
});

describe('TAXONOMY_ROOTS', () => {
  it('tem exatamente 14 entradas', () => {
    expect(TAXONOMY_ROOTS).toHaveLength(14);
  });

  it('cada entrada tem code, label e icon preenchidos', () => {
    for (const root of TAXONOMY_ROOTS) {
      expect(root.code).toBeTruthy();
      expect(root.label).toBeTruthy();
      expect(root.icon).toBeTruthy();
    }
  });

  it('todos os codes são únicos', () => {
    const codes = TAXONOMY_ROOTS.map(r => r.code);
    expect(new Set(codes).size).toBe(14);
  });
});
```

- [ ] **Step 2: Rodar o teste para verificar que falha**

```bash
cd /home/sergio/fintech-core/frontend && npm test -- taxonomy-suggestions 2>&1 | tail -20
```

Resultado esperado: erro de módulo não encontrado (arquivo não existe ainda).

- [ ] **Step 3: Implementar `taxonomy-suggestions.ts`**

Criar `frontend/src/app/features/category/taxonomy-mapping/taxonomy-suggestions.ts`:

```typescript
export interface TaxonomyRoot {
  code: string;
  label: string;
  icon: string;
}

export const TAXONOMY_ROOTS: TaxonomyRoot[] = [
  { code: 'INCOME',        label: 'Renda',              icon: 'attach_money'    },
  { code: 'HOUSING',       label: 'Moradia',             icon: 'home'            },
  { code: 'FOOD',          label: 'Alimentação',         icon: 'restaurant'      },
  { code: 'TRANSPORT',     label: 'Transporte',          icon: 'directions_car'  },
  { code: 'HEALTH',        label: 'Saúde',               icon: 'favorite'        },
  { code: 'LEISURE',       label: 'Lazer',               icon: 'movie'           },
  { code: 'EDUCATION',     label: 'Educação',            icon: 'school'          },
  { code: 'CLOTHING',      label: 'Vestuário',           icon: 'checkroom'       },
  { code: 'HOME_GOODS',    label: 'Casa & Decoração',    icon: 'weekend'         },
  { code: 'SUBSCRIPTIONS', label: 'Assinaturas',         icon: 'subscriptions'   },
  { code: 'PERSONAL_CARE', label: 'Cuidados Pessoais',   icon: 'spa'             },
  { code: 'PETS',          label: 'Pets',                icon: 'pets'            },
  { code: 'FINANCIAL',     label: 'Financeiro',          icon: 'account_balance' },
  { code: 'GIFTS',         label: 'Presentes e Doações', icon: 'card_giftcard'   },
];

// Mapeamento PT-BR → código. Chave sempre normalizada (sem acentos, lowercase).
const SUGGESTIONS: Record<string, string> = {
  'renda':             'INCOME',
  'receitas':          'INCOME',
  'salario':           'INCOME',
  'moradia':           'HOUSING',
  'casa':              'HOUSING',
  'alimentacao':       'FOOD',
  'comida':            'FOOD',
  'transporte':        'TRANSPORT',
  'saude':             'HEALTH',
  'lazer':             'LEISURE',
  'entretenimento':    'LEISURE',
  'educacao':          'EDUCATION',
  'roupas':            'CLOTHING',
  'vestuario':         'CLOTHING',
  'assinaturas':       'SUBSCRIPTIONS',
  'cuidados pessoais': 'PERSONAL_CARE',
  'beleza':            'PERSONAL_CARE',
  'pets':              'PETS',
  'animais':           'PETS',
  'financeiro':        'FINANCIAL',
  'financas':          'FINANCIAL',
  'presentes':         'GIFTS',
  'doacoes':           'GIFTS',
};

// NFD decompõe acentos compostos; replace remove combining diacritics (U+0300–U+036F)
function normalize(name: string): string {
  return name.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
}

export function getSuggestion(name: string): string | null {
  if (!name) return null;
  return SUGGESTIONS[normalize(name)] ?? null;
}
```

- [ ] **Step 4: Rodar os testes para confirmar que passam**

```bash
cd /home/sergio/fintech-core/frontend && npm test -- taxonomy-suggestions 2>&1 | tail -20
```

Resultado esperado: todos os testes passam.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/category/taxonomy-mapping/taxonomy-suggestions.ts \
        frontend/src/app/features/category/taxonomy-mapping/taxonomy-suggestions.spec.ts
git commit -m "feat(category): adiciona taxonomy-suggestions com tabela PT-BR e testes unitários"
```

---

### Task 4: Frontend — `TaxonomyMappingComponent`

**Files:**
- Create: `frontend/src/app/features/category/taxonomy-mapping/taxonomy-mapping.ts`
- Create: `frontend/src/app/features/category/taxonomy-mapping/taxonomy-mapping.html`
- Create: `frontend/src/app/features/category/taxonomy-mapping/taxonomy-mapping.scss`

**Conceitos pedagógicos:**
- `pendingMappings` é `signal<Map<string, string>>`. Maps são objetos mutáveis — se chamarmos `m.set(key, val)` na mesma referência, o signal não detecta mudança (a referência não mudou). Por isso: `update(m => new Map(m).set(...))` — criamos uma nova instância a cada operação, garantindo que os `computed()` que dependem desse signal recomputem.
- `forkJoin(calls)` combina um array de Observables em paralelo e emite um array com todos os resultados quando **todos** completam. Se qualquer um falhar, o Observable inteiro falha. Ideal aqui: enviamos todos os PUTs ao mesmo tempo e esperamos todos terminarem antes de atualizar a UI.
- O redirect em `ngOnInit` é a segunda linha de defesa: mesmo que alguém acesse `/categories/taxonomy` diretamente, o componente verifica a role e redireciona para `/dashboard`. O sidenav (primeira linha) já oculta o link para não-admins.
- `autoSelectNext()` é chamada após `selectCode()` para avançar automaticamente para a próxima categoria não mapeada, melhorando o fluxo de trabalho.

**Pré-requisito:** Task 2 deve estar concluída (Orval rodado) para que `CategoryResponseDTO.taxonomyCode` exista no TypeScript.

- [ ] **Step 1: Criar `taxonomy-mapping.ts`**

```typescript
// frontend/src/app/features/category/taxonomy-mapping/taxonomy-mapping.ts
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { forkJoin } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthService } from '../../../core/services/auth';
import { CategoriesService } from '../../../core/api/categories/categories.service';
import { CategoryResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';
import { getSuggestion, TAXONOMY_ROOTS, TaxonomyRoot } from './taxonomy-suggestions';

@Component({
  selector: 'app-taxonomy-mapping',
  standalone: true,
  imports: [
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  templateUrl: './taxonomy-mapping.html',
  styleUrl: './taxonomy-mapping.scss',
})
export class TaxonomyMappingComponent implements OnInit {
  private router = inject(Router);
  private authService = inject(AuthService);
  private categoriesService = inject(CategoriesService);
  private snackBar = inject(MatSnackBar);

  readonly taxonomyRoots: TaxonomyRoot[] = TAXONOMY_ROOTS;

  rootCategories = signal<CategoryResponseDTO[]>([]);
  selectedCategory = signal<CategoryResponseDTO | null>(null);
  // Map: categoryId → taxonomyCode pendente (ainda não salvo)
  pendingMappings = signal<Map<string, string>>(new Map());
  loading = signal(true);
  saving = signal(false);

  // Conjunto de todos os códigos já em uso (salvos + pendentes)
  usedCodes = computed(() => {
    const saved = this.rootCategories()
      .filter(c => c.taxonomyCode)
      .map(c => c.taxonomyCode!);
    const pending = [...this.pendingMappings().values()];
    return new Set([...saved, ...pending]);
  });

  // Sugestão automática para a categoria selecionada
  suggestedCode = computed(() => {
    const cat = this.selectedCategory();
    return cat ? getSuggestion(cat.name) : null;
  });

  // Quantas categorias já têm código (salvo ou pendente)
  mappedCount = computed(() => {
    const pending = this.pendingMappings();
    return this.rootCategories().filter(c => c.taxonomyCode || pending.has(c.id)).length;
  });

  ngOnInit(): void {
    if (!this.authService.isAdmin()) {
      this.router.navigate(['/dashboard']);
      return;
    }
    this.categoriesService.listCategories({ includeArchived: false }).subscribe({
      next: (cats) => {
        // listCategories retorna árvore completa; filtramos apenas as raízes
        this.rootCategories.set(cats.filter(c => !c.parentId));
        this.loading.set(false);
        this.autoSelectNext();
      },
      error: () => this.loading.set(false),
    });
  }

  selectCategory(cat: CategoryResponseDTO): void {
    this.selectedCategory.set(cat);
  }

  selectCode(code: string): void {
    const cat = this.selectedCategory();
    if (!cat) return;
    // Não permite selecionar código em uso por OUTRA categoria
    if (this.isCodeUsedByOther(code)) return;

    // Nova referência de Map para disparar recompute nos computed()
    this.pendingMappings.update(m => new Map(m).set(cat.id, code));
    this.autoSelectNext();
  }

  // Retorna true se o código está em uso por uma categoria diferente da selecionada
  isCodeUsedByOther(code: string): boolean {
    const cat = this.selectedCategory();
    const currentCode = cat
      ? (this.pendingMappings().get(cat.id) ?? cat.taxonomyCode ?? null)
      : null;
    return this.usedCodes().has(code) && code !== currentCode;
  }

  // Código efetivo de uma categoria: pendente > salvo > null
  getEffectiveCode(cat: CategoryResponseDTO): string | null {
    return this.pendingMappings().get(cat.id) ?? cat.taxonomyCode ?? null;
  }

  hasSuggestion(cat: CategoryResponseDTO): boolean {
    return getSuggestion(cat.name) !== null;
  }

  save(): void {
    const mappings = this.pendingMappings();
    if (!mappings.size) return;

    const calls = [...mappings.entries()].map(([id, taxonomyCode]) => {
      const cat = this.rootCategories().find(c => c.id === id)!;
      return this.categoriesService.updateCategory(id, {
        name: cat.name,
        icon: cat.icon,
        color: cat.color,
        parentId: cat.parentId ?? null,
        taxonomyCode,
      });
    });

    this.saving.set(true);
    forkJoin(calls).subscribe({
      next: (updated) => {
        // Atualiza rootCategories com os valores retornados pelo backend
        this.rootCategories.update(cats =>
          cats.map(c => updated.find(r => r.id === c.id) ?? c)
        );
        this.pendingMappings.set(new Map());
        this.saving.set(false);
        this.snackBar.open('Mapeamento salvo com sucesso.', 'OK', { duration: 3000 });
      },
      error: () => {
        this.saving.set(false);
        this.snackBar.open('Erro ao salvar mapeamento.', 'Fechar', { duration: 4000 });
      },
    });
  }

  private autoSelectNext(): void {
    const pending = this.pendingMappings();
    const next = this.rootCategories().find(
      c => !c.taxonomyCode && !pending.has(c.id)
    );
    this.selectedCategory.set(next ?? null);
  }
}
```

- [ ] **Step 2: Criar `taxonomy-mapping.html`**

```html
<!-- frontend/src/app/features/category/taxonomy-mapping/taxonomy-mapping.html -->
<div class="mapping-page">

  <header class="mapping-header">
    <div>
      <h2 class="mapping-title">Mapeamento de taxonomia</h2>
      <p class="mapping-subtitle">Associe suas categorias ao padrão do sistema</p>
    </div>
    <div class="mapping-actions">
      @if (!loading()) {
        <span class="mapping-progress-label">
          {{ mappedCount() }}/{{ rootCategories().length }} mapeadas
        </span>
        <div class="progress-bar">
          <div class="progress-fill"
               [style.width.%]="rootCategories().length
                 ? (mappedCount() / rootCategories().length * 100)
                 : 0">
          </div>
        </div>
      }
      <button mat-flat-button color="primary"
              [disabled]="!pendingMappings().size || saving()"
              (click)="save()">
        @if (saving()) {
          <mat-spinner [diameter]="18"></mat-spinner>
        } @else {
          Salvar
        }
      </button>
    </div>
  </header>

  @if (loading()) {
    <div class="loading-state">
      <mat-spinner [diameter]="40"></mat-spinner>
    </div>
  } @else {
    <div class="mapping-panels">

      <!-- Painel esquerdo: categorias do tenant -->
      <div class="panel">
        <div class="panel-label">Suas categorias (raízes)</div>
        <div class="category-list">
          @for (cat of rootCategories(); track cat.id) {
            <div class="category-item"
                 [class.selected]="selectedCategory()?.id === cat.id"
                 [class.ambiguous]="!hasSuggestion(cat) && !getEffectiveCode(cat)"
                 (click)="selectCategory(cat)">
              <span class="category-dot" [style.background]="cat.color"></span>
              <span class="category-name">{{ cat.name }}</span>
              @if (getEffectiveCode(cat); as code) {
                <span class="code-badge"
                      [class.pending]="pendingMappings().has(cat.id)">
                  {{ code }}{{ pendingMappings().has(cat.id) ? ' *' : '' }}
                </span>
              } @else if (!hasSuggestion(cat)) {
                <span class="ambiguous-badge">? ambígua</span>
              }
            </div>
          }
        </div>
      </div>

      <!-- Painel direito: taxonomia padrão -->
      <div class="panel">
        <div class="panel-label">Taxonomia padrão — clique para associar</div>
        <div class="taxonomy-list">
          @for (root of taxonomyRoots; track root.code) {
            <div class="taxonomy-item"
                 [class.suggested]="suggestedCode() === root.code && !isCodeUsedByOther(root.code)"
                 [class.disabled]="!selectedCategory() || isCodeUsedByOther(root.code)"
                 [matTooltip]="isCodeUsedByOther(root.code) ? 'Código já em uso por outra categoria' : ''"
                 (click)="selectCode(root.code)">
              <mat-icon class="taxonomy-icon">{{ root.icon }}</mat-icon>
              <div class="taxonomy-info">
                <span class="taxonomy-code">{{ root.code }}</span>
                <span class="taxonomy-label">{{ root.label }}</span>
              </div>
              @if (suggestedCode() === root.code && !isCodeUsedByOther(root.code)) {
                <span class="suggested-badge">sugerido ✦</span>
              } @else if (isCodeUsedByOther(root.code)) {
                <span class="in-use-label">em uso</span>
              }
            </div>
          }
        </div>
      </div>

    </div>

    @if (selectedCategory()) {
      <div class="hint-bar">
        <mat-icon>info</mat-icon>
        <strong>{{ selectedCategory()!.name }}</strong> selecionada —
        @if (suggestedCode()) {
          clique em <strong>{{ suggestedCode() }}</strong> para confirmar a sugestão, ou escolha outro código.
        } @else {
          sem sugestão automática; escolha o código mais adequado no painel direito.
        }
      </div>
    }
  }

</div>
```

- [ ] **Step 3: Criar `taxonomy-mapping.scss`**

```scss
// frontend/src/app/features/category/taxonomy-mapping/taxonomy-mapping.scss
.mapping-page {
  padding: 24px;
  max-width: 960px;
  margin: 0 auto;
}

.mapping-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
  gap: 16px;
  flex-wrap: wrap;
}

.mapping-title {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
}

.mapping-subtitle {
  font-size: 13px;
  color: var(--mat-sys-on-surface-variant, #49454f);
  margin: 4px 0 0;
}

.mapping-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.mapping-progress-label {
  font-size: 12px;
  color: var(--mat-sys-on-surface-variant, #49454f);
}

.progress-bar {
  width: 100px;
  height: 6px;
  background: var(--mat-sys-surface-variant, #e0e0e0);
  border-radius: 3px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: var(--mat-sys-primary, #1976d2);
  border-radius: 3px;
  transition: width 0.3s ease;
}

.loading-state {
  display: flex;
  justify-content: center;
  padding: 64px 0;
}

.mapping-panels {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
}

.panel-label {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--mat-sys-on-surface-variant, #49454f);
  margin-bottom: 8px;
}

// ── categorias do tenant ──────────────────────────────────────────
.category-list,
.taxonomy-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.category-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: 8px;
  border: 1px solid var(--mat-sys-outline-variant, #cac4d0);
  background: var(--mat-sys-surface, #fff);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;

  &:hover { background: var(--mat-sys-surface-container-low, #f5f5f5); }

  &.selected {
    border: 2px solid var(--mat-sys-primary, #1976d2);
    background: var(--mat-sys-primary-container, #e8eaf6);
  }

  &.ambiguous {
    border: 1px dashed #ffb74d;
    background: #fff3e0;
  }
}

.category-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
}

.category-name {
  flex: 1;
  font-size: 13px;
}

.code-badge {
  font-size: 11px;
  color: var(--mat-sys-primary, #1976d2);
  font-weight: 600;

  &.pending { color: #f57c00; }
}

.ambiguous-badge {
  font-size: 11px;
  color: #ff9800;
}

// ── taxonomia padrão ──────────────────────────────────────────────
.taxonomy-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: 8px;
  border: 1px solid var(--mat-sys-outline-variant, #cac4d0);
  background: var(--mat-sys-surface, #fff);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;

  &:hover:not(.disabled) { background: var(--mat-sys-surface-container-low, #f5f5f5); }

  &.suggested {
    border: 2px solid var(--mat-sys-primary, #1976d2);
    background: var(--mat-sys-primary-container, #e8eaf6);
  }

  &.disabled {
    opacity: 0.45;
    cursor: not-allowed;
  }
}

.taxonomy-icon {
  color: var(--mat-sys-primary, #1976d2);
  font-size: 20px;
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}

.taxonomy-info {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.taxonomy-code {
  font-size: 12px;
  font-weight: 600;
}

.taxonomy-label {
  font-size: 11px;
  color: var(--mat-sys-on-surface-variant, #49454f);
}

.suggested-badge {
  font-size: 10px;
  color: var(--mat-sys-primary, #1976d2);
  font-weight: 600;
}

.in-use-label {
  font-size: 10px;
  color: var(--mat-sys-on-surface-variant, #49454f);
}

// ── hint bar ──────────────────────────────────────────────────────
.hint-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 20px;
  padding: 12px 16px;
  background: var(--mat-sys-secondary-container, #e3f2fd);
  border-radius: 8px;
  font-size: 13px;
  color: var(--mat-sys-on-secondary-container, #1565c0);

  mat-icon {
    font-size: 18px;
    width: 18px;
    height: 18px;
    flex-shrink: 0;
  }
}
```

- [ ] **Step 4: Verificar compilação TypeScript**

```bash
cd /home/sergio/fintech-core/frontend && npx tsc --noEmit 2>&1 | grep -i "taxonomy-mapping" | head -20
```

Resultado esperado: sem erros para os arquivos de taxonomy-mapping.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/category/taxonomy-mapping/taxonomy-mapping.ts \
        frontend/src/app/features/category/taxonomy-mapping/taxonomy-mapping.html \
        frontend/src/app/features/category/taxonomy-mapping/taxonomy-mapping.scss
git commit -m "feat(category): adiciona TaxonomyMappingComponent com painéis lado a lado"
```

---

### Task 5: Frontend — rota e item no sidenav

**Files:**
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/components/shell/shell.ts`

**Conceitos pedagógicos:**
- A rota `categories/taxonomy` deve vir ANTES de `categories/:id` no array. O Angular Router avalia rotas em ordem; `:id` é um wildcard que capturaria literalmente "taxonomy" se viesse primeiro, fazendo o componente de formulário ser carregado no lugar do mapeamento.
- O item `adminOnly: true` no sidenav é a primeira linha de defesa — oculta o link da UI. O redirect em `ngOnInit` do componente é a segunda linha — bloqueia acesso direto por URL. Juntos implementam a regra de defesa em profundidade do projeto.

- [ ] **Step 1: Adicionar a rota em `app.routes.ts`**

Inserir a rota `categories/taxonomy` ANTES de `categories/:id`. O trecho de categorias ficará:

```typescript
{
  path: 'categories',
  loadComponent: () =>
    import('./features/category/category-list/category-list').then(m => m.CategoryList)
},
{
  path: 'categories/new',
  loadComponent: () =>
    import('./features/category/category-form/category-form').then(m => m.CategoryForm)
},
{
  path: 'categories/taxonomy',
  loadComponent: () =>
    import('./features/category/taxonomy-mapping/taxonomy-mapping')
      .then(m => m.TaxonomyMappingComponent)
},
{
  path: 'categories/:id',
  loadComponent: () =>
    import('./features/category/category-form/category-form').then(m => m.CategoryForm)
},
```

- [ ] **Step 2: Adicionar item no sidenav em `shell.ts`**

No array `navItems`, inserir após o item de Categorias:

```typescript
{ label: 'Taxonomia', icon: 'account_tree', route: '/categories/taxonomy', adminOnly: true },
```

O array completo ficará:

```typescript
readonly navItems: NavItem[] = [
  { label: 'Dashboard',  icon: 'dashboard',    route: '/dashboard' },
  { label: 'Transações', icon: 'swap_horiz',   route: '/transactions' },
  { label: 'Faturas',    icon: 'receipt_long', route: '/invoices' },
  { label: 'Contas',     icon: 'credit_card',  route: '/accounts' },
  { label: 'Categorias', icon: 'category',     route: '/categories' },
  { label: 'Taxonomia',  icon: 'account_tree', route: '/categories/taxonomy', adminOnly: true },
  { label: 'Equipe',     icon: 'group',        route: '/team', adminOnly: true },
];
```

- [ ] **Step 3: Verificar compilação TypeScript completa**

```bash
cd /home/sergio/fintech-core/frontend && npx tsc --noEmit 2>&1 | head -30
```

Resultado esperado: sem erros.

- [ ] **Step 4: Rodar a suite de testes completa**

```bash
cd /home/sergio/fintech-core/frontend && npm test -- --run 2>&1 | tail -20
```

Resultado esperado: todos os testes anteriores passam (sem regressão).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/app.routes.ts \
        frontend/src/app/components/shell/shell.ts
git commit -m "feat(category): adiciona rota /categories/taxonomy e link no sidenav (admin only)"
```

---

## Self-Review

### Cobertura da spec

| Requisito da spec | Task |
|---|---|
| `taxonomyCode` em `CategoryCreateDTO` Java (campo usado por `update()`) | Task 1 |
| ADMIN guard em `CategoryService.update()` com `AccessDeniedException` | Task 1 |
| 3 testes em `CategoryServiceTest` + 4 call sites existentes atualizados | Task 1 |
| `taxonomyCode` no schema `CategoryCreateDTO` em `openapi.yaml` | Task 2 |
| Orval regenerado (inclui `CategoryResponseDTO.taxonomyCode` que já estava no yaml) | Task 2 |
| `taxonomy-suggestions.ts` com `SUGGESTIONS`, `TAXONOMY_ROOTS`, `getSuggestion()` | Task 3 |
| Testes unitários de `getSuggestion()` — PT-BR, case-insensitive, sem acentos, null | Task 3 |
| `TaxonomyMappingComponent` — dois painéis lado a lado | Task 4 |
| Seleção de categoria → destaca código sugerido | Task 4 |
| Click no código → conecta e avança para próxima não mapeada | Task 4 |
| Mapeamento 1:1 — código em uso desabilitado (exceto o da selecionada) | Task 4 |
| Categorias ambíguas com borda laranja tracejada | Task 4 |
| Salvar via `forkJoin` com snackbar e atualização de `rootCategories` | Task 4 |
| Redirect para `/dashboard` se não ADMIN (defesa em profundidade) | Task 4 |
| Rota `/categories/taxonomy` antes de `/categories/:id` | Task 5 |
| Link "Taxonomia" no sidenav com `adminOnly: true` | Task 5 |

Sem lacunas.

### Placeholder scan

Nenhum "TBD", "TODO", "similar a Task N" ou seção incompleta.

### Consistência de tipos

- `CategoryCreateDTO` Java: 5 campos (`name, icon, color, parentId, taxonomyCode`) — usado em Task 1 (testes, 4 call sites corrigidos) e implicitamente em Task 4 (via Orval)
- `CategoryResponseDTO.taxonomyCode` — adicionado via Orval no Task 2; usado em Task 4 (`cat.taxonomyCode`)
- `TaxonomyRoot` — definido em `taxonomy-suggestions.ts` (Task 3); importado em `taxonomy-mapping.ts` (Task 4)
- `getSuggestion(name: string): string | null` — assinatura consistente entre Task 3 (implementação) e Task 4 (uso em `suggestedCode` computed e `hasSuggestion`)
- `listCategories({ includeArchived: false })` — usa `ListCategoriesParams.includeArchived?: boolean`, correto conforme o schema gerado
- `updateCategory(id, CategoryCreateDTO)` — após Task 2, `CategoryCreateDTO` TypeScript tem `taxonomyCode?: string | null`
