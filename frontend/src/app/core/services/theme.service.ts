import { Injectable, signal, effect } from '@angular/core';

export type AppTheme = 'light' | 'dark' | 'auto';

@Injectable({
  providedIn: 'root',
})
export class ThemeService {
  private mediaQuery: MediaQueryList | null =
    typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia('(prefers-color-scheme: dark)')
      : null;

  theme = signal<AppTheme>(this.getInitialTheme());
  isDark = signal<boolean>(false);

  constructor() {
    this.updateResolvedTheme();

    if (this.mediaQuery) {
      this.mediaQuery.addEventListener('change', () => {
        if (this.theme() === 'auto') {
          this.updateResolvedTheme();
        }
      });
    }

    effect(() => {
      const dark = this.isDark();
      if (typeof document !== 'undefined') {
        const root = document.documentElement;
        if (dark) {
          root.classList.add('dark-theme');
          root.setAttribute('data-theme', 'dark');
          root.style.colorScheme = 'dark';
        } else {
          root.classList.remove('dark-theme');
          root.setAttribute('data-theme', 'light');
          root.style.colorScheme = 'light';
        }
      }
    });
  }

  private getInitialTheme(): AppTheme {
    try {
      const saved = localStorage.getItem('fintech_theme') as AppTheme | null;
      if (saved && (saved === 'light' || saved === 'dark' || saved === 'auto')) {
        return saved;
      }
    } catch {}
    return 'auto';
  }

  private updateResolvedTheme(): void {
    const current = this.theme();
    let dark = false;
    if (current === 'dark') {
      dark = true;
    } else if (current === 'light') {
      dark = false;
    } else {
      dark = this.mediaQuery ? this.mediaQuery.matches : false;
    }
    this.isDark.set(dark);
  }

  setTheme(newTheme: AppTheme): void {
    this.theme.set(newTheme);
    try {
      localStorage.setItem('fintech_theme', newTheme);
    } catch {}
    this.updateResolvedTheme();
  }

  toggleTheme(): void {
    const next = this.isDark() ? 'light' : 'dark';
    this.setTheme(next);
  }
}
