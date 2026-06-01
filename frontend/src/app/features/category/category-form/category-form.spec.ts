import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, ActivatedRoute } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, it, expect, vi, beforeEach } from 'vitest';

import { CategoryForm } from './category-form';
import { CategoriesService } from '../../../core/api/categories/categories.service';
import { CategoryResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

const mockParent: CategoryResponseDTO = {
  id: 'parent-1',
  name: 'Alimentação',
  icon: 'restaurant',
  color: '#e74c3c',
  archived: false,
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
    // O serviço gerado pelo Orval tem overloads, o que faz o TypeScript resolver
    // o ReturnType para o overload mais genérico. O cast via unknown é necessário
    // para compatibilidade com Vitest ao mockar métodos com múltiplos overloads.
    vi.spyOn(service, 'listCategories').mockReturnValue(
      of([mockParent]) as unknown as ReturnType<typeof service.listCategories>,
    );

    fixture = TestBed.createComponent(CategoryForm);
    component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
  });

  describe('icon picker', () => {
    it('selectIcon define o valor do ícone no formulário', () => {
      component.selectIcon('restaurant');
      expect(component.form.get('icon')?.value).toBe('restaurant');
    });

    it('selectIcon atualiza o sinal selectedIcon', () => {
      component.selectIcon('shopping_cart');
      expect(component.selectedIcon()).toBe('shopping_cart');
    });

    it('ícone padrão inicial é "folder"', () => {
      expect(component.selectedIcon()).toBe('folder');
    });
  });

  describe('herança do pai', () => {
    it('herda ícone e cor ao selecionar um pai', async () => {
      component.form.get('parentId')!.setValue('parent-1');
      await fixture.whenStable();
      expect(component.form.getRawValue().icon).toBe('restaurant');
      expect(component.form.getRawValue().color).toBe('#e74c3c');
      expect(component.selectedIcon()).toBe('restaurant');
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
      expect(component.inheritedFromName()).toBeNull();
    });

    it('restaura ícone e cor padrão ao remover o pai', async () => {
      component.form.get('parentId')!.setValue('parent-1');
      await fixture.whenStable();
      component.form.get('parentId')!.setValue(null);
      await fixture.whenStable();
      expect(component.form.getRawValue().icon).toBe('folder');
      expect(component.form.getRawValue().color).toBe('#3f51b5');
      expect(component.selectedIcon()).toBe('folder');
    });
  });

  describe('modo edição com categoria filha', () => {
    it('aplica herança ao carregar categoria com pai', async () => {
      const childCat = {
        id: 'child-1',
        name: 'Lanches',
        icon: 'restaurant',
        color: '#e74c3c',
        parentId: 'parent-1',
        children: [],
      };

      TestBed.resetTestingModule();
      await TestBed.configureTestingModule({
        imports: [CategoryForm, NoopAnimationsModule],
        providers: [
          provideZonelessChangeDetection(),
          provideRouter([]),
          provideHttpClient(),
          provideHttpClientTesting(),
          { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => 'child-1' } } } },
        ],
      }).compileComponents();

      const svc = TestBed.inject(CategoriesService);
      vi.spyOn(svc, 'listCategories').mockReturnValue(of([mockParent]) as unknown as ReturnType<typeof svc.listCategories>);
      vi.spyOn(svc, 'getCategory').mockReturnValue(of(childCat) as unknown as ReturnType<typeof svc.getCategory>);

      const editFixture = TestBed.createComponent(CategoryForm);
      const editComponent = editFixture.componentInstance;
      editFixture.detectChanges();
      await editFixture.whenStable();

      expect(editComponent.inherited()).toBe(true);
      expect(editComponent.form.get('icon')?.disabled).toBe(true);
      expect(editComponent.form.get('color')?.disabled).toBe(true);
      expect(editComponent.inheritedFromName()).toBe('Alimentação');
    });
  });
});
