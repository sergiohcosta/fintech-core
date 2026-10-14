import { Component, inject, signal, DestroyRef } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { BreakpointObserver } from '@angular/cdk/layout';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatTabLink, MatTabNav, MatTabNavPanel } from '@angular/material/tabs';
import { AuthService } from '../../../core/services/auth';

// Labels curtos mantêm as quatro rotas acessíveis em uma única faixa no celular;
// o mesmo tab-nav e os mesmos links permanecem no desktop para não mudar a navegação.
@Component({
  selector: 'app-planning-shell',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet, MatTabNav, MatTabNavPanel, MatTabLink],
  styleUrl: './planning-shell.scss',
  template: `
    <nav mat-tab-nav-bar [tabPanel]="panel" class="planning-tabs">
      <a mat-tab-link routerLink="current" routerLinkActive #c="routerLinkActive" [active]="c.isActive">
        {{ isCompact() ? 'Atual' : 'Ciclo atual' }}
      </a>
      <a mat-tab-link routerLink="cycles" routerLinkActive #h="routerLinkActive" [active]="h.isActive">
        {{ isCompact() ? 'Ciclos' : 'Histórico' }}
      </a>
      <a mat-tab-link routerLink="recurring" routerLinkActive #r="routerLinkActive" [active]="r.isActive">
        Recorrentes
      </a>
      @if (isAdmin()) {
        <a mat-tab-link routerLink="settings" routerLinkActive #s="routerLinkActive" [active]="s.isActive">
          {{ isCompact() ? 'Ajustes' : 'Configurações' }}
        </a>
      }
    </nav>
    <mat-tab-nav-panel #panel>
      <router-outlet />
    </mat-tab-nav-panel>
  `,
})
export class PlanningShellComponent {
  protected readonly isAdmin = inject(AuthService).isAdmin;
  readonly isCompact = signal(false);

  private readonly destroyRef = inject(DestroyRef);
  private readonly breakpointObserver = inject(BreakpointObserver);

  constructor() {
    this.breakpointObserver.observe('(max-width: 719.98px)')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(({ matches }) => this.isCompact.set(matches));
  }
}
