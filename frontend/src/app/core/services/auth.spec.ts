import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';

import { AuthService } from './auth';

describe('AuthService', () => {
  let authService: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideRouter([]),
      ],
    });
    authService = TestBed.inject(AuthService);
  });

  it('should be created', () => {
    expect(authService).toBeTruthy();
  });

  it('isAdmin retorna false quando não há usuário logado', () => {
    authService.currentUser.set(null);
    expect(authService.isAdmin()).toBe(false);
  });

  it('isAdmin retorna true para role ADMIN', () => {
    authService.currentUser.set({ sub: 'a@test.com', name: 'A', tenant_id: 'tid', role: 'ADMIN', exp: 9999999999 });
    expect(authService.isAdmin()).toBe(true);
  });

  it('isAdmin retorna false para role USER', () => {
    authService.currentUser.set({ sub: 'b@test.com', name: 'B', tenant_id: 'tid', role: 'USER', exp: 9999999999 });
    expect(authService.isAdmin()).toBe(false);
  });
});
