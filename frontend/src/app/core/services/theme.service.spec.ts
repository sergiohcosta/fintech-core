import { describe, it, expect, beforeEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  let service: ThemeService;

  beforeEach(() => {
    localStorage.clear();
    document.documentElement.className = '';
    document.documentElement.removeAttribute('data-theme');

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [ThemeService],
    });

    service = TestBed.inject(ThemeService);
  });

  it('inicia por padrão com tema auto', () => {
    expect(service.theme()).toBe('auto');
  });

  it('setTheme altera o tema e persiste no localStorage', () => {
    service.setTheme('dark');
    expect(service.theme()).toBe('dark');
    expect(service.isDark()).toBe(true);
    expect(localStorage.getItem('fintech_theme')).toBe('dark');

    service.setTheme('light');
    expect(service.theme()).toBe('light');
    expect(service.isDark()).toBe(false);
    expect(localStorage.getItem('fintech_theme')).toBe('light');
  });

  it('toggleTheme alterna entre dark e light', () => {
    service.setTheme('light');
    service.toggleTheme();
    expect(service.isDark()).toBe(true);
    expect(service.theme()).toBe('dark');

    service.toggleTheme();
    expect(service.isDark()).toBe(false);
    expect(service.theme()).toBe('light');
  });

  it('recupera o tema persistido no localStorage ao inicializar', () => {
    localStorage.setItem('fintech_theme', 'dark');
    TestBed.resetTestingModule();
    const newService = TestBed.inject(ThemeService);
    expect(newService.theme()).toBe('dark');
    expect(newService.isDark()).toBe(true);
  });
});
