import { describe, it, expect, beforeEach, vi } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { BreakpointObserver } from '@angular/cdk/layout';
import { of } from 'rxjs';
import { signal } from '@angular/core';
import { ShellComponent } from './shell';
import { AuthService } from '../../core/services/auth';

describe('ShellComponent', () => {
  let fixture: ComponentFixture<ShellComponent>;
  let component: ShellComponent;
  let mockAuthService: any;
  let mockBreakpointObserver: any;

  beforeEach(async () => {
    localStorage.clear();
    TestBed.resetTestingModule();

    mockAuthService = {
      currentUser: signal({ name: 'Maria Silva', email: 'maria@test.com' }),
      isAdmin: signal(false),
      logout: vi.fn(),
    };
    mockBreakpointObserver = {
      observe: vi.fn().mockReturnValue(of({ matches: false })),
    };

    await TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: mockAuthService },
        { provide: BreakpointObserver, useValue: mockBreakpointObserver },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ShellComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('inicia no desktop como pinned=true por padrão', () => {
    expect(component.isCompact()).toBe(false);
    expect(component.isPinned()).toBe(true);
    expect(component.isRail()).toBe(false);
  });

  it('alterna para modo retrátil (rail) e persiste no localStorage', () => {
    component.togglePin();
    expect(component.isPinned()).toBe(false);
    expect(component.isRail()).toBe(true);
    expect(localStorage.getItem('fintech_sidenav_pinned')).toBe('false');

    component.togglePin();
    expect(component.isPinned()).toBe(true);
    expect(component.isRail()).toBe(false);
    expect(localStorage.getItem('fintech_sidenav_pinned')).toBe('true');
  });

  it('toggleSidenav no desktop alterna o estado de fixação (pinned)', () => {
    expect(component.isPinned()).toBe(true);
    component.toggleSidenav();
    expect(component.isPinned()).toBe(false);
    component.toggleSidenav();
    expect(component.isPinned()).toBe(true);
  });

  it('calcula as iniciais do usuário corretamente para o avatar', () => {
    expect(component.userInitials()).toBe('MS');
  });

  it('permite alternar o tema da aplicação', () => {
    expect(component.themeService).toBeDefined();
    component.themeService.setTheme('light');
    expect(component.themeService.isDark()).toBe(false);

    component.themeService.toggleTheme();
    expect(component.themeService.isDark()).toBe(true);
  });

  it('logout chama authService.logout', () => {
    component.logout();
    expect(mockAuthService.logout).toHaveBeenCalled();
  });
});
