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
import { MatDividerModule } from '@angular/material/divider';
import { AuthService } from '../../core/services/auth';
import { BottomTabBarComponent } from './bottom-tab-bar/bottom-tab-bar';

export interface NavItem {
  label: string;
  icon: string;
  route: string;
  adminOnly?: boolean;
}

export interface NavSection {
  title?: string;
  items: NavItem[];
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
    MatDividerModule,
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

  readonly navSections: NavSection[] = [
    {
      title: 'Finanças',
      items: [
        { label: 'Dashboard', icon: 'dashboard', route: '/dashboard' },
        { label: 'Transações', icon: 'swap_horiz', route: '/transactions' },
        { label: 'Planejamento', icon: 'event_note', route: '/planning' },
        { label: 'Faturas', icon: 'receipt_long', route: '/invoices' },
      ],
    },
    {
      title: 'Operações',
      items: [
        { label: 'Importar Extrato', icon: 'document_scanner', route: '/import' },
        { label: 'Recorrências', icon: 'autorenew', route: '/recurrences' },
      ],
    },
    {
      title: 'Cadastros & Configurações',
      items: [
        { label: 'Contas & Carteiras', icon: 'credit_card', route: '/accounts' },
        { label: 'Categorias', icon: 'category', route: '/categories' },
        { label: 'Meu perfil', icon: 'person', route: '/profile' },
        { label: 'Equipe', icon: 'group', route: '/team', adminOnly: true },
      ],
    },
  ];

  get navItems(): NavItem[] {
    return this.navSections.flatMap(s => s.items);
  }

  toggleSidenav() {
    if (!this.isCompact()) return;
    this.sidenavOpen.set(!this.sidenavOpen());
  }

  logout() {
    this.authService.logout();
  }
}
