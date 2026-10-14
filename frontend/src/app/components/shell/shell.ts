import { Component, computed, inject, signal } from '@angular/core';
import { BreakpointObserver } from '@angular/cdk/layout';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatListModule } from '@angular/material/list';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthService } from '../../core/services/auth';
import { BottomTabBarComponent } from './bottom-tab-bar/bottom-tab-bar';

interface NavItem {
  label: string;
  icon: string;
  route: string;
  adminOnly?: boolean;
}

@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatToolbarModule,
    MatSidenavModule,
    MatListModule,
    MatIconModule,
    MatButtonModule,
    MatTooltipModule,
    BottomTabBarComponent,
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class ShellComponent {
  private authService = inject(AuthService);
  private breakpointObserver = inject(BreakpointObserver);

  isCompact = signal(false);
  sidenavOpen = signal(false);
  sidenavMode = computed<'over' | 'side'>(() => this.isCompact() ? 'over' : 'side');
  sidenavOpened = computed(() => !this.isCompact());

  constructor() {
    // Mantém o drawer alinhado ao breakpoint compartilhado com os estilos do app.
    this.breakpointObserver.observe('(max-width: 719.98px)')
      .pipe(takeUntilDestroyed())
      .subscribe(({ matches }) => {
        this.isCompact.set(matches);
        if (matches) this.sidenavOpen.set(false);
      });
  }

  // computed() relê o signal do AuthService automaticamente.
  // No modelo Zoneless, isso garante que a toolbar atualiza sem zone.js.
  userName = computed(() => this.authService.currentUser()?.name ?? '');
  isAdmin = this.authService.isAdmin;

  readonly navItems: NavItem[] = [
    { label: 'Dashboard',  icon: 'dashboard',    route: '/dashboard' },
    { label: 'Transações', icon: 'swap_horiz',   route: '/transactions' },
    { label: 'Importar',   icon: 'document_scanner', route: '/import' },
    { label: 'Faturas',    icon: 'receipt_long', route: '/invoices' },
    { label: 'Planejamento', icon: 'event_note', route: '/planning' },
    { label: 'Recorrências', icon: 'autorenew',  route: '/recurrences' },
    { label: 'Contas',     icon: 'credit_card',  route: '/accounts' },
    { label: 'Categorias', icon: 'category',     route: '/categories' },
    { label: 'Equipe',     icon: 'group',        route: '/team', adminOnly: true },
  ];

  toggleSidenav() {
    if (!this.isCompact()) return;
    this.sidenavOpen.set(!this.sidenavOpen());
  }

  logout() {
    this.authService.logout();
  }
}
