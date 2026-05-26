import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth-guard';

export const routes: Routes = [
  { path: '', redirectTo: 'login', pathMatch: 'full' },

  {
    path: 'login',
    loadComponent: () => import('./features/auth/login/login').then(m => m.LoginComponent)
  },
  {
    path: 'register',
    loadComponent: () => import('./features/auth/register/register').then(m => m.RegisterComponent)
  },

  // Rota de layout: path vazio + canActivate aqui protege TODOS os filhos de uma vez.
  // O ShellComponent renderiza toolbar + sidenav e tem seu próprio <router-outlet>.
  {
    path: '',
    loadComponent: () => import('./components/shell/shell').then(m => m.ShellComponent),
    canActivate: [authGuard],
    children: [
      {
        path: 'dashboard',
        loadComponent: () => import('./features/dashboard/dashboard').then(m => m.DashboardComponent)
      },
      {
        path: 'credit-cards',
        loadComponent: () => import('./features/credit-card/components/card-list').then(m => m.CardListComponent)
      },
      {
        path: 'credit-cards/new',
        loadComponent: () => import('./features/credit-card/components/card-form/card-form').then(m => m.CardFormComponent)
      },
      {
        path: 'categories',
        loadComponent: () => import('./features/category/category-list/category-list').then(m => m.CategoryList)
      },
      {
        path: 'categories/new',
        loadComponent: () => import('./features/category/category-form/category-form').then(m => m.CategoryForm)
      },
      {
        path: 'categories/:id',
        loadComponent: () => import('./features/category/category-form/category-form').then(m => m.CategoryForm)
      },
      {
        path: 'transactions',
        loadComponent: () => import('./features/transaction/transaction-list/transaction-list').then(m => m.TransactionList)
      },
      {
        path: 'transactions/new',
        loadComponent: () => import('./features/transaction/transaction-form/transaction-form').then(m => m.TransactionForm)
      },
      {
        path: 'transactions/:id',
        loadComponent: () => import('./features/transaction/transaction-form/transaction-form').then(m => m.TransactionForm)
      },
    ]
  }
];
