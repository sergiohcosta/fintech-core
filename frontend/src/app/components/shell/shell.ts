import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatListModule } from '@angular/material/list';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthService } from '../../core/services/auth';

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
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class ShellComponent {
  private authService = inject(AuthService);

  sidenavOpened = signal(true);

  // computed() relê o signal do AuthService automaticamente.
  // No modelo Zoneless, isso garante que a toolbar atualiza sem zone.js.
  userName = computed(() => this.authService.currentUser()?.name ?? '');
  isAdmin = this.authService.isAdmin;

  readonly navItems: NavItem[] = [
    { label: 'Dashboard',  icon: 'dashboard',   route: '/dashboard' },
    { label: 'Transações', icon: 'swap_horiz',  route: '/transactions' },
    { label: 'Contas',    icon: 'credit_card', route: '/accounts' },
    { label: 'Categorias', icon: 'category',    route: '/categories' },
    { label: 'Equipe',     icon: 'group',       route: '/team', adminOnly: true },
  ];

  toggleSidenav() {
    this.sidenavOpened.update(opened => !opened);
  }

  logout() {
    this.authService.logout();
  }
}
