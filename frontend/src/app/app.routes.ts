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
  {
    path: 'accept-invite',
    loadComponent: () =>
      import('./features/auth/accept-invite/accept-invite').then(m => m.AcceptInviteComponent)
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
        path: 'accounts',
        loadComponent: () => import('./features/account/account-list/account-list').then(m => m.AccountList)
      },
      {
        path: 'accounts/new',
        loadComponent: () => import('./features/account/account-form/account-form').then(m => m.AccountForm)
      },
      {
        path: 'accounts/:id',
        loadComponent: () => import('./features/account/account-form/account-form').then(m => m.AccountForm)
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
        path: 'categories/taxonomy',
        loadComponent: () =>
          import('./features/category/taxonomy-mapping/taxonomy-mapping').then(
            m => m.TaxonomyMappingComponent
          )
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
      {
        path: 'team',
        loadComponent: () => import('./features/team/team/team').then(m => m.TeamComponent)
      },
      {
        path: 'invoices',
        loadComponent: () => import('./features/invoice/invoice-list/invoice-list').then(m => m.InvoiceList)
      },
      {
        path: 'invoices/:id',
        loadComponent: () => import('./features/invoice/invoice-detail/invoice-detail').then(m => m.InvoiceDetail)
      },
      {
        path: 'planning',
        loadChildren: () =>
          import('./features/planning/planning.routes').then(m => m.planningRoutes),
      },
    ]
  }
];
