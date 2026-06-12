import { Routes } from '@angular/router';

export const planningRoutes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./planning-shell/planning-shell').then(m => m.PlanningShellComponent),
    children: [
      { path: '', redirectTo: 'current', pathMatch: 'full' },
      {
        path: 'current',
        loadComponent: () =>
          import('./budget-cycle-current/budget-cycle-current').then(m => m.BudgetCycleCurrentComponent),
      },
      {
        path: 'cycles',
        loadComponent: () =>
          import('./budget-cycle-list/budget-cycle-list').then(m => m.BudgetCycleList),
      },
      {
        path: 'cycles/:id',
        loadComponent: () =>
          import('./budget-cycle-detail/budget-cycle-detail').then(m => m.BudgetCycleDetail),
      },
      {
        path: 'recurring',
        loadComponent: () =>
          import('./recurring-item-list/recurring-item-list').then(m => m.RecurringItemList),
      },
    ],
  },
];
