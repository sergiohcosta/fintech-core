import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, from, map, switchMap } from 'rxjs';
import { jwtDecode } from 'jwt-decode';

import { RegisterResponseDTO } from '../api/fintechSaaSAPI.schemas';
import { BiometricAuthService } from './biometric-auth.service';
import { TokenStorageService } from './token-storage.service';

export interface TokenPayload {
  sub: string;
  name: string;
  tenant_id: string;
  role: 'ADMIN' | 'USER';
  exp: number;
}

export interface RegisterRequest {
  name: string;
  document?: string;
  adminName: string;
  adminEmail: string;
  password: string;
}

// AuthService mantém o estado de sessão em memória; a inicialização aguarda o storage assíncrono
// antes do primeiro guard para que uma recarga não redirecione um usuário válido ao login.
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly tokenStorage = inject(TokenStorageService);
  private readonly biometricAuth = inject(BiometricAuthService);
  private readonly API_URL = '/auth';

  readonly currentUser = signal<TokenPayload | null>(null);
  readonly isAdmin = computed(() => this.currentUser()?.role === 'ADMIN');

  register(data: RegisterRequest): Observable<RegisterResponseDTO> {
    return this.http.post<RegisterResponseDTO>(`${this.API_URL}/register`, data);
  }

  login(credentials: { email: string; password: string }): Observable<{ token: string }> {
    return this.http.post<{ token: string }>(`${this.API_URL}/login`, credentials).pipe(
      switchMap(response => from(this.tokenStorage.saveToken(response.token)).pipe(
        map(() => {
          this.restoreSession(response.token);
          return response;
        }),
      )),
    );
  }

  forgotPassword(email: string): Observable<void> {
    return this.http.post<void>(`${this.API_URL}/forgot-password`, { email });
  }

  resetPassword(token: string, newPassword: string): Observable<void> {
    return this.http.post<void>(`${this.API_URL}/reset-password`, { token, newPassword });
  }

  async logout(): Promise<void> {
    this.currentUser.set(null);
    await this.tokenStorage.clearToken();
    await this.router.navigate(['/login']);
  }

  async setToken(token: string): Promise<void> {
    await this.tokenStorage.saveToken(token);
    this.restoreSession(token);
  }

  getToken(): string | null {
    return this.tokenStorage.token();
  }

  isAuthenticated(): boolean {
    const token = this.tokenStorage.token();
    if (!token) return false;
    try {
      const valid = jwtDecode<TokenPayload>(token).exp > Math.floor(Date.now() / 1000);
      if (!valid) {
        this.currentUser.set(null);
        void this.tokenStorage.clearToken();
      }
      return valid;
    } catch {
      this.currentUser.set(null);
      void this.tokenStorage.clearToken();
      return false;
    }
  }

  async bootstrap(): Promise<void> {
    await this.tokenStorage.init();
    await this.biometricAuth.checkAvailability();
    this.restoreSession();
  }

  restoreSession(token = this.tokenStorage.token()): boolean {
    if (!token) {
      this.currentUser.set(null);
      return false;
    }
    try {
      const decoded = jwtDecode<TokenPayload>(token);
      if (decoded.exp <= Math.floor(Date.now() / 1000)) {
        this.currentUser.set(null);
        void this.tokenStorage.clearToken();
        return false;
      }
      this.currentUser.set(decoded);
      return true;
    } catch {
      this.currentUser.set(null);
      void this.tokenStorage.clearToken();
      return false;
    }
  }
}
