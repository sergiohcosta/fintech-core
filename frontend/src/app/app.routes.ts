import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth-guard';

export const routes: Routes = [
  { path: '', redirectTo: 'login', pathMatch: 'full' },

  {
    path: 'login',
    title: 'Entrar',
    loadComponent: () => import('./features/auth/login/login').then(m => m.LoginComponent)
  },
  {
    path: 'register',
    title: 'Criar Conta',
    loadComponent: () => import('./features/auth/register/register').then(m => m.RegisterComponent)
  },
  {
    path: 'accept-invite',
    title: 'Aceitar Convite',
    loadComponent: () =>
      import('./features/auth/accept-invite/accept-invite').then(m => m.AcceptInviteComponent)
  },
  {
    path: 'forgot-password',
    title: 'Recuperar Senha',
    loadComponent: () =>
      import('./features/auth/forgot-password/forgot-password').then(m => m.ForgotPasswordComponent)
  },
  {
    path: 'reset-password',
    title: 'Redefinir Senha',
    loadComponent: () =>
      import('./features/auth/reset-password/reset-password').then(m => m.ResetPasswordComponent)
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
        title: 'Início',
        loadComponent: () => import('./features/dashboard/dashboard').then(m => m.DashboardComponent)
      },
      {
        path: 'accounts',
        title: 'Minhas Contas',
        loadComponent: () => import('./features/account/account-list/account-list').then(m => m.AccountList)
      },
      {
        path: 'accounts/new',
        title: 'Nova Conta',
        loadComponent: () => import('./features/account/account-form/account-form').then(m => m.AccountForm)
      },
      {
        path: 'accounts/:id',
        title: 'Editar Conta',
        loadComponent: () => import('./features/account/account-form/account-form').then(m => m.AccountForm)
      },
      {
        path: 'categories',
        title: 'Categorias',
        loadComponent: () => import('./features/category/category-list/category-list').then(m => m.CategoryList)
      },
      {
        path: 'categories/new',
        title: 'Nova Categoria',
        loadComponent: () => import('./features/category/category-form/category-form').then(m => m.CategoryForm)
      },
      {
        path: 'categories/taxonomy',
        title: 'Mapeamento de Categorias',
        loadComponent: () =>
          import('./features/category/taxonomy-mapping/taxonomy-mapping').then(
            m => m.TaxonomyMappingComponent
          )
      },
      {
        path: 'categories/:id',
        title: 'Editar Categoria',
        loadComponent: () => import('./features/category/category-form/category-form').then(m => m.CategoryForm)
      },
      {
        path: 'transactions',
        title: 'Transações',
        loadComponent: () => import('./features/transaction/transaction-list/transaction-list').then(m => m.TransactionList)
      },
      {
        // CRÍTICO: precisa vir antes de 'transactions/:id', senão o param :id captura
        // a string "timeline" e tenta abrir o form de edição.
        path: 'transactions/timeline',
        title: 'Linha do Tempo',
        loadComponent: () =>
          import('./features/transaction/transaction-timeline/transaction-timeline').then(
            m => m.TransactionTimelineComponent
          )
      },
      {
        path: 'transactions/new',
        title: 'Nova Transação',
        loadComponent: () => import('./features/transaction/transaction-form/transaction-form').then(m => m.TransactionForm)
      },
      {
        path: 'transactions/:id',
        title: 'Editar Transação',
        loadComponent: () => import('./features/transaction/transaction-form/transaction-form').then(m => m.TransactionForm)
      },
      {
        path: 'team',
        title: 'Equipe',
        loadComponent: () => import('./features/team/team/team').then(m => m.TeamComponent)
      },
      {
        path: 'profile',
        title: 'Meu Perfil',
        loadComponent: () => import('./features/profile/profile').then(m => m.ProfileComponent)
      },
      {
        path: 'invoices',
        title: 'Faturas de Cartão',
        loadComponent: () => import('./features/invoice/invoice-list/invoice-list').then(m => m.InvoiceList)
      },
      {
        path: 'invoices/:id',
        title: 'Detalhe da Fatura',
        loadComponent: () => import('./features/invoice/invoice-detail/invoice-detail').then(m => m.InvoiceDetail)
      },
      {
        path: 'planning',
        title: 'Planejamento Mensal',
        loadChildren: () =>
          import('./features/planning/planning.routes').then(m => m.planningRoutes),
      },
      {
        path: 'recurrences',
        title: 'Recorrências',
        loadComponent: () =>
          import('./features/recurrence/recurrence-list/recurrence-list').then(m => m.RecurrenceList)
      },
      {
        path: 'import',
        title: 'Importar Extrato',
        loadComponent: () => import('./features/import/import').then(m => m.ImportComponent)
      },
      {
        path: 'import/:id',
        title: 'Importar Extrato',
        loadComponent: () => import('./features/import/import').then(m => m.ImportComponent)
      },
      {
        path: 'imports',
        title: 'Histórico de Importações',
        loadComponent: () =>
          import('./features/import/import-history/import-history').then(m => m.ImportHistory)
      },
    ]
  }
];
