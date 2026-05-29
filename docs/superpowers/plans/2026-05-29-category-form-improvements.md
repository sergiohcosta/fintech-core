# Melhorias no Formulário de Categorias — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Substituir o dropdown de ícones por um grid expansível (#22) e adicionar herança automática de cor/ícone ao selecionar categoria pai (#20).

**Architecture:** Mudanças puramente no frontend, em três arquivos do componente `category-form`. Sem alterações de backend ou contrato de API. A lógica de herança usa o `valueChanges` do `parentId` (Observable RxJS, correto para Reactive Forms) para disparar `patchValue` e `disable/enable` nos campos `icon` e `color`.

**Tech Stack:** Angular 21 Zoneless, Signals (`signal`, `computed`), Reactive Forms, Angular Material 3, Vitest + TestBed

---

## Arquivos alterados

| Arquivo | Responsabilidade |
|---|---|
| `frontend/src/app/features/category/category-form/category-form.ts` | Lógica de icon picker e de herança |
| `frontend/src/app/features/category/category-form/category-form.html` | Template do grid e hints |
| `frontend/src/app/features/category/category-form/category-form.scss` | Estilos do icon picker |
| `frontend/src/app/features/category/category-form/category-form.spec.ts` | Testes (arquivo novo) |

---

## Task 1: Testes do icon picker (TDD — escrever antes da implementação)

**Files:**
- Create: `frontend/src/app/features/category/category-form/category-form.spec.ts`

- [ ] **Step 1: Criar o arquivo de testes com o setup e os testes do icon picker**

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, it, expect, vi, beforeEach } from 'vitest';

import { CategoryForm } from './category-form';
import { CategoriesService } from '../../../core/api/categories/categories.service';

const mockParent = {
  id: 'parent-1',
  name: 'Alimentação',
  icon: 'restaurant',
  color: '#e74c3c',
  children: [],
};

describe('CategoryForm', () => {
  let component: CategoryForm;
  let fixture: ComponentFixture<CategoryForm>;
  let service: CategoriesService;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CategoryForm, NoopAnimationsModule],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    service = TestBed.inject(CategoriesService);
    vi.spyOn(service, 'listCategories').mockReturnValue(of([mockParent]));

    fixture = TestBed.createComponent(CategoryForm);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  describe('icon picker', () => {
    it('toggleIconPicker abre o picker quando fechado', () => {
      component.toggleIconPicker();
      expect(component.iconPickerOpen()).toBe(true);
    });

    it('toggleIconPicker fecha o picker quando aberto', () => {
      component.iconPickerOpen.set(true);
      component.toggleIconPicker();
      expect(component.iconPickerOpen()).toBe(false);
    });

    it('toggleIconPicker não faz nada quando inherited é true', () => {
      component.inherited.set(true);
      component.toggleIconPicker();
      expect(component.iconPickerOpen()).toBe(false);
    });

    it('selectIcon define o valor do ícone no formulário', () => {
      component.selectIcon('restaurant');
      expect(component.form.get('icon')?.value).toBe('restaurant');
    });

    it('selectIcon fecha o picker após a seleção', () => {
      component.iconPickerOpen.set(true);
      component.selectIcon('restaurant');
      expect(component.iconPickerOpen()).toBe(false);
    });
  });
});
```

- [ ] **Step 2: Rodar os testes e confirmar que falham (métodos ainda não existem)**

```bash
cd frontend && npm test -- --reporter=verbose 2>&1 | grep -E "FAIL|PASS|✓|✗|error" | head -20
```

Esperado: erros de compilação ou falhas porque `iconPickerOpen`, `inherited` e `selectIcon/toggleIconPicker` não existem ainda.

---

## Task 2: Implementar icon picker no `category-form.ts`

**Files:**
- Modify: `frontend/src/app/features/category/category-form/category-form.ts`

- [ ] **Step 1: Substituir o conteúdo completo do arquivo**

```typescript
import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { CategoriesService } from '../../../core/api/categories/categories.service';
import { CategoryResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

interface CategoryOption {
  id: string;
  name: string;
  level: number;
  color: string;
  icon: string;
}

@Component({
  selector: 'app-category-form',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatSelectModule,
    MatIconModule,
    MatSnackBarModule,
    RouterLink
  ],
  templateUrl: './category-form.html',
  styleUrl: './category-form.scss'
})
export class CategoryForm implements OnInit {
  private fb = inject(FormBuilder);
  private service = inject(CategoriesService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private snackBar = inject(MatSnackBar);

  form!: FormGroup;
  isEditMode = signal(false);
  categoryId = signal<string | null>(null);
  parentOptions = signal<CategoryOption[]>([]);
  iconPickerOpen = signal(false);
  inherited = signal(false);
  inheritedFromName = signal<string | null>(null);

  availableIcons = [
    'shopping_cart', 'restaurant', 'directions_car', 'home', 'build',
    'medical_services', 'school', 'fitness_center', 'flight', 'local_gas_station',
    'payments', 'account_balance', 'savings', 'trending_up', 'work',
    'pets', 'redeem', 'videogame_asset', 'subscriptions', 'electrical_services',
    'face', 'family_restroom', 'celebration', 'movie', 'checkroom'
  ];

  ngOnInit(): void {
    this.initForm();
    this.setupParentInheritance();
    this.checkEditMode();
    this.loadParentOptions();
  }

  private initForm() {
    this.form = this.fb.group({
      name: ['', [Validators.required, Validators.minLength(3)]],
      icon: ['folder', Validators.required],
      color: ['#3f51b5', Validators.required],
      parentId: [null]
    });
  }

  private setupParentInheritance() {
    this.form.get('parentId')!.valueChanges.subscribe((parentId: string | null) => {
      if (parentId) {
        const parent = this.parentOptions().find(opt => opt.id === parentId);
        if (parent) {
          this.form.patchValue({ icon: parent.icon, color: parent.color }, { emitEvent: false });
          this.form.get('icon')!.disable({ emitEvent: false });
          this.form.get('color')!.disable({ emitEvent: false });
          this.inherited.set(true);
          this.inheritedFromName.set(parent.name);
        }
      } else {
        this.form.get('icon')!.enable({ emitEvent: false });
        this.form.get('color')!.enable({ emitEvent: false });
        this.form.patchValue({ icon: 'folder', color: '#3f51b5' }, { emitEvent: false });
        this.inherited.set(false);
        this.inheritedFromName.set(null);
      }
    });
  }

  private loadParentOptions() {
    this.service.listCategories().subscribe(categories => {
      const flattened: CategoryOption[] = [];
      this.flattenCategories(categories, flattened, 0);
      if (this.isEditMode() && this.categoryId()) {
        this.parentOptions.set(flattened.filter(opt => opt.id !== this.categoryId()));
      } else {
        this.parentOptions.set(flattened);
      }
    });
  }

  private flattenCategories(categories: CategoryResponseDTO[], result: CategoryOption[], level: number) {
    categories.forEach(cat => {
      if (this.isEditMode() && cat.id === this.categoryId()) return;
      result.push({ id: cat.id, name: cat.name, level, color: cat.color, icon: cat.icon });
      if (cat.children?.length) {
        this.flattenCategories(cat.children, result, level + 1);
      }
    });
  }

  private checkEditMode() {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.isEditMode.set(true);
      this.categoryId.set(id);
      this.service.getCategory(id).subscribe({
        next: (cat) => this.form.patchValue(cat, { emitEvent: false }),
        error: () => this.showError('Erro ao carregar categoria.')
      });
    }
  }

  selectIcon(icon: string) {
    this.form.get('icon')!.setValue(icon);
    this.iconPickerOpen.set(false);
  }

  toggleIconPicker() {
    if (!this.inherited()) {
      this.iconPickerOpen.update(open => !open);
    }
  }

  onSubmit() {
    if (this.form.invalid) return;
    const data = this.form.getRawValue();
    const request = (this.isEditMode() && this.categoryId())
      ? this.service.updateCategory(this.categoryId()!, data)
      : this.service.createCategory(data);

    request.subscribe({
      next: () => this.onSuccess('Categoria salva com sucesso!'),
      error: () => this.showError('Erro ao salvar categoria.')
    });
  }

  private onSuccess(msg: string) {
    this.snackBar.open(msg, 'OK', { duration: 3000 });
    this.router.navigate(['/categories']);
  }

  private showError(msg: string) {
    this.snackBar.open(msg, 'Fechar', { duration: 5000 });
  }
}
```

- [ ] **Step 2: Rodar testes do icon picker e confirmar que passam**

```bash
cd frontend && npm test -- --reporter=verbose 2>&1 | grep -E "icon picker|✓|✗|PASS|FAIL" | head -20
```

Esperado: 5 testes passando no bloco `icon picker`.

---

## Task 3: Atualizar HTML e SCSS para o icon picker

**Files:**
- Modify: `frontend/src/app/features/category/category-form/category-form.html`
- Modify: `frontend/src/app/features/category/category-form/category-form.scss`

- [ ] **Step 1: Substituir o conteúdo completo do HTML**

```html
<div class='form-container mat-elevation-z4'>
    <header>
        <h1>{{ isEditMode() ? 'Editar Categoria' : 'Nova Categoria' }}</h1>
        <p>Preencha os dados abaixo</p>
    </header>

    <form [formGroup]='form' (ngSubmit)='onSubmit()'>
        <div class='grid-layout'>

            <!-- Categoria Pai -->
            <mat-form-field appearance='outline' class='full-width'>
                <mat-label>Categoria Pai (Opcional)</mat-label>
                <mat-select formControlName='parentId'>
                    <mat-option [value]='null'>-- Nenhuma (Categoria Raiz) --</mat-option>
                    @for (opt of parentOptions(); track opt.id) {
                        <mat-option [value]='opt.id'>
                            <span [style.padding-left.px]='opt.level * 20'>
                                {{ opt.level > 0 ? '↳ ' : '' }}{{ opt.name }}
                            </span>
                        </mat-option>
                    }
                </mat-select>
                <mat-hint>Deixe vazio para criar uma categoria principal</mat-hint>
            </mat-form-field>

            <!-- Nome -->
            <mat-form-field appearance='outline' class='full-width'>
                <mat-label>Nome da Categoria</mat-label>
                <input matInput formControlName='name' placeholder='Ex: Alimentação'>
                <mat-error *ngIf='form.get("name")?.hasError("required")'>Obrigatório</mat-error>
            </mat-form-field>

            <!-- Ícone (grid expansível) -->
            <div class='icon-picker' [class.disabled]='inherited()'>
                <label class='icon-picker-label'>Ícone *</label>
                <button type='button' class='icon-picker-trigger' (click)='toggleIconPicker()' [disabled]='inherited()'>
                    <mat-icon>{{ form.getRawValue().icon }}</mat-icon>
                    <span>{{ form.getRawValue().icon }}</span>
                    <mat-icon class='chevron'>{{ iconPickerOpen() ? 'expand_less' : 'expand_more' }}</mat-icon>
                </button>
                @if (iconPickerOpen()) {
                    <div class='icon-picker-grid'>
                        @for (icon of availableIcons; track icon) {
                            <button
                                type='button'
                                class='icon-btn'
                                [class.selected]="form.getRawValue().icon === icon"
                                (click)='selectIcon(icon)'
                                [title]='icon'>
                                <mat-icon>{{ icon }}</mat-icon>
                            </button>
                        }
                    </div>
                }
                @if (inherited()) {
                    <span class='inherited-hint'>Herdado de {{ inheritedFromName() }}</span>
                }
            </div>

            <!-- Cor -->
            <mat-form-field appearance='outline'>
                <mat-label>Cor de Identificação</mat-label>
                <input matInput type='color' formControlName='color' style='height: 40px; cursor: pointer;'>
                @if (inherited()) {
                    <mat-hint>Herdado de {{ inheritedFromName() }}</mat-hint>
                }
            </mat-form-field>

        </div>

        <div class='actions'>
            <button mat-button type='button' routerLink='/categories'>Cancelar</button>
            <button mat-flat-button color='primary' type='submit' [disabled]='form.invalid'>
                {{ isEditMode() ? 'Salvar Alterações' : 'Criar Categoria' }}
            </button>
        </div>
    </form>
</div>
```

- [ ] **Step 2: Adicionar estilos do icon picker ao SCSS (append ao final do arquivo existente)**

```scss
// --- Icon Picker ---

.icon-picker {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.icon-picker-label {
  font-size: 0.75rem;
  color: rgba(0, 0, 0, 0.6);
  margin-bottom: 2px;
}

.icon-picker-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  height: 56px;
  border: 1px solid rgba(0, 0, 0, 0.38);
  border-radius: 4px;
  background: white;
  cursor: pointer;
  font-size: 1rem;
  color: inherit;
  transition: border-color 0.2s;
  width: 100%;
  text-align: left;

  &:hover:not(:disabled) {
    border-color: rgba(0, 0, 0, 0.87);
  }

  &:disabled {
    opacity: 0.6;
    cursor: not-allowed;
    background: rgba(0, 0, 0, 0.04);
  }

  .chevron {
    margin-left: auto;
    color: rgba(0, 0, 0, 0.54);
  }
}

.icon-picker-grid {
  display: grid;
  grid-template-columns: repeat(5, 40px);
  gap: 6px;
  padding: 10px;
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 4px;
  background: #fafafa;
}

.icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;

  &:hover {
    background: rgba(63, 81, 181, 0.1);
    border-color: rgba(63, 81, 181, 0.3);
  }

  &.selected {
    background: #3f51b5;
    color: white;
    border-color: #3f51b5;
  }
}

.inherited-hint {
  font-size: 0.75rem;
  color: #3f51b5;
  margin-top: 2px;
}
```

- [ ] **Step 3: Confirmar que todos os testes ainda passam**

```bash
cd frontend && npm test -- --reporter=verbose 2>&1 | grep -E "✓|✗|PASS|FAIL" | head -20
```

Esperado: todos os testes passando, sem erros de compilação.

- [ ] **Step 4: Commit do icon picker**

```bash
git add frontend/src/app/features/category/category-form/
git commit -m "feat(categorias): substitui dropdown de ícones por grid expansível (issue #22)"
```

---

## Task 4: Testes de herança do pai (TDD — escrever antes de verificar)

**Files:**
- Modify: `frontend/src/app/features/category/category-form/category-form.spec.ts`

- [ ] **Step 1: Adicionar bloco `describe('herança do pai', ...)` ao arquivo de testes**

Adicionar após o bloco `describe('icon picker', ...)`, ainda dentro do `describe('CategoryForm', ...)` externo:

```typescript
  describe('herança do pai', () => {
    it('herda ícone e cor ao selecionar um pai', async () => {
      component.form.get('parentId')!.setValue('parent-1');
      await fixture.whenStable();
      expect(component.form.getRawValue().icon).toBe('restaurant');
      expect(component.form.getRawValue().color).toBe('#e74c3c');
    });

    it('desabilita os campos de ícone e cor ao selecionar pai', async () => {
      component.form.get('parentId')!.setValue('parent-1');
      await fixture.whenStable();
      expect(component.form.get('icon')?.disabled).toBe(true);
      expect(component.form.get('color')?.disabled).toBe(true);
    });

    it('define inherited como true ao selecionar pai', async () => {
      component.form.get('parentId')!.setValue('parent-1');
      await fixture.whenStable();
      expect(component.inherited()).toBe(true);
    });

    it('define inheritedFromName com o nome do pai', async () => {
      component.form.get('parentId')!.setValue('parent-1');
      await fixture.whenStable();
      expect(component.inheritedFromName()).toBe('Alimentação');
    });

    it('reabilita os campos ao remover o pai', async () => {
      component.form.get('parentId')!.setValue('parent-1');
      await fixture.whenStable();
      component.form.get('parentId')!.setValue(null);
      await fixture.whenStable();
      expect(component.form.get('icon')?.disabled).toBe(false);
      expect(component.form.get('color')?.disabled).toBe(false);
    });

    it('reseta inherited para false ao remover o pai', async () => {
      component.form.get('parentId')!.setValue('parent-1');
      await fixture.whenStable();
      component.form.get('parentId')!.setValue(null);
      await fixture.whenStable();
      expect(component.inherited()).toBe(false);
    });

    it('restaura ícone e cor padrão ao remover o pai', async () => {
      component.form.get('parentId')!.setValue('parent-1');
      await fixture.whenStable();
      component.form.get('parentId')!.setValue(null);
      await fixture.whenStable();
      expect(component.form.getRawValue().icon).toBe('folder');
      expect(component.form.getRawValue().color).toBe('#3f51b5');
    });
  });
```

- [ ] **Step 2: Rodar os testes e confirmar que os novos passam (a lógica já foi implementada na Task 2)**

```bash
cd frontend && npm test -- --reporter=verbose 2>&1 | grep -E "herança|✓|✗|PASS|FAIL" | head -20
```

Esperado: 7 novos testes passando no bloco `herança do pai`.

- [ ] **Step 3: Rodar suite completa**

```bash
cd frontend && npm test 2>&1 | tail -10
```

Esperado: todos os testes passando, zero falhas.

- [ ] **Step 4: Commit dos testes de herança**

```bash
git add frontend/src/app/features/category/category-form/category-form.spec.ts
git commit -m "test(categorias): adiciona testes de icon picker e herança de pai (issues #20 e #22)"
```

---

## Self-Review

**Cobertura do spec:**
- ✅ #22: `mat-select` substituído por grid expansível com `iconPickerOpen` e `selectIcon`
- ✅ #22: Grid fechado por padrão; abre ao clicar; fecha ao selecionar ícone
- ✅ #20: `CategoryOption` estendido com `color` e `icon`
- ✅ #20: `valueChanges` do `parentId` dispara `patchValue` + `disable` + signals
- ✅ #20: Remoção do pai reabilita campos e restaura defaults
- ✅ #20: `onSubmit` usa `getRawValue()` para incluir campos desabilitados
- ✅ #20: `checkEditMode` usa `{emitEvent: false}` para não disparar herança ao carregar
- ✅ #20: `inherited()` bloqueia o `toggleIconPicker`
- ✅ Testes para todos os comportamentos públicos

**Placeholder scan:** nenhum TBD, TODO ou "similar ao task N" encontrado.

**Consistência de tipos:**
- `CategoryOption` definido na Task 2 com `color` e `icon` — usado corretamente em `setupParentInheritance` e `flattenCategories`
- `form.getRawValue()` usado tanto em `onSubmit` (Task 2) quanto nos testes de herança (Task 4)
- `iconPickerOpen`, `inherited`, `inheritedFromName` definidos na Task 2, referenciados no HTML (Task 3) e nos testes (Tasks 1 e 4)
