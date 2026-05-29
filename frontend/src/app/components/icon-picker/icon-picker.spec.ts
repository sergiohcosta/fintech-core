import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { describe, it, expect, beforeEach } from 'vitest';

import { IconPicker } from './icon-picker';

describe('IconPicker', () => {
  let component: IconPicker;
  let fixture: ComponentFixture<IconPicker>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [IconPicker, NoopAnimationsModule],
      providers: [provideZonelessChangeDetection()],
    }).compileComponents();

    fixture = TestBed.createComponent(IconPicker);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('isOpen inicia como false', () => {
    expect(component.isOpen()).toBe(false);
  });

  it('toggle abre o picker quando fechado', () => {
    component.toggle();
    expect(component.isOpen()).toBe(true);
  });

  it('toggle fecha o picker quando aberto', () => {
    component.isOpen.set(true);
    component.toggle();
    expect(component.isOpen()).toBe(false);
  });

  it('toggle não abre quando disabled é true', () => {
    fixture.componentRef.setInput('disabled', true);
    component.toggle();
    expect(component.isOpen()).toBe(false);
  });

  it('select emite o ícone via iconSelected e fecha o picker', () => {
    component.isOpen.set(true);
    let emitted = '';
    component.iconSelected.subscribe((icon: string) => { emitted = icon; });

    component.select('restaurant');

    expect(emitted).toBe('restaurant');
    expect(component.isOpen()).toBe(false);
  });

  it('select limpa o searchTerm após seleção', () => {
    component.searchTerm.set('rest');
    component.select('restaurant');
    expect(component.searchTerm()).toBe('');
  });

  it('filteredIcons retorna todos os ícones quando searchTerm está vazio', () => {
    expect(component.filteredIcons().length).toBe(component.availableIcons.length);
  });

  it('filteredIcons filtra ícones pelo searchTerm', () => {
    component.searchTerm.set('home');
    const filtered = component.filteredIcons();
    expect(filtered.every((i: string) => i.includes('home'))).toBe(true);
  });

  it('filteredIcons retorna array vazio quando nenhum ícone bate com a pesquisa', () => {
    component.searchTerm.set('xyznonexistent');
    expect(component.filteredIcons().length).toBe(0);
  });

  it('selectedIcon padrão é "folder"', () => {
    expect(component.selectedIcon()).toBe('folder');
  });

  it('selectedIcon reflete o input passado', () => {
    fixture.componentRef.setInput('selectedIcon', 'home');
    expect(component.selectedIcon()).toBe('home');
  });
});
