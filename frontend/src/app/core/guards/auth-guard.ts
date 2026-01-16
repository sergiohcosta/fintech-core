import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
// CORREÇÃO AQUI: Importando do arquivo 'auth' e não 'auth.service'
import { AuthService } from '../services/auth';

export const authGuard: CanActivateFn = (route, state) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  // Lógica de Security by Design
  if (authService.getToken()) {
    return true;
  } else {
    router.navigate(['/login']);
    return false;
  }
};