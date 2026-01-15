import { Routes } from '@angular/router';

export const routes: Routes = [
  { 
    path: 'register', 
    loadComponent: () => import('./features/auth/register/register').then(m => m.RegisterComponent) 
  },
  { path: '', redirectTo: 'register', pathMatch: 'full' }
];