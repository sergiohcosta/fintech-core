import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth-guard';

export const routes: Routes = [
  { path: '', redirectTo: 'login', pathMatch: 'full' },

  {
    path: 'register',
    loadComponent: () => import('./features/auth/register/register').then(m => m.RegisterComponent)
  },

  {
    path: 'login',
    loadComponent: () => import('./features/auth/login/login').then(m => m.LoginComponent)
  },
  {
    path: 'dashboard',
    loadComponent: () => import('./features/dashboard/dashboard').then(m => m.DashboardComponent),
    canActivate: [authGuard]
  },
  {
    path: 'credit-cards',
    loadComponent: () => import('./features/credit-card/components/card-list').then(m => m.CardListComponent),
    canActivate: [authGuard]
  },
  {
    path: 'credit-cards/new',
    loadComponent: () => import('./features/credit-card/components/card-form/card-form').then(m => m.CardFormComponent),
    canActivate: [authGuard]
  },
  {
    path: 'credit-cards/:id',
    loadComponent: () => import('./features/credit-card/components/card-form/card-form').then(m => m.CardFormComponent),
    canActivate: [authGuard]
  }
];