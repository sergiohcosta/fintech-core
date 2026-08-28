import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../services/auth';

/**
 * Intercepta respostas 401 (token expirado/inválido) e redireciona ao login.
 * Ignora endpoints públicos (/auth/*) para não interferir no fluxo de login.
 *
 * Fecha a issue #150: sessão expirada em uso sem tratamento de 401.
 */
export const authErrorInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);

  // Endpoints públicos não devem disparar logout (ex.: login com credencial errada = 401 legítimo)
  if (req.url.includes('/auth/')) {
    return next(req);
  }

  return next(req).pipe(
    catchError((error) => {
      if (error.status === 401) {
        authService.logout();
      }
      return throwError(() => error);
    })
  );
};
