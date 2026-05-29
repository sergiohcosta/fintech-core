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
import { CategoryResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

const mockParent: CategoryResponseDTO = {
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
    it('toggleIconPicker abre o picker quando fechado', () => {
      component.toggleIconPicker();
      expect(component.iconPickerOpen()).toBe(true);
    });

    it('toggleIconPicker fecha o picker quando aberto', () => {
      component.iconPickerOpen.set(true);
      component.toggleIconPicker();
      expect(component.iconPickerOpen()).toBe(false);
    });

    it('toggleIconPicker não altera o picker quando inherited é true', () => {
      component.inherited.set(true);
      component.iconPickerOpen.set(false);
      component.toggleIconPicker();
      expect(component.iconPickerOpen()).toBe(false);

      component.iconPickerOpen.set(true);
      component.toggleIconPicker();
      expect(component.iconPickerOpen()).toBe(true);
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
});
