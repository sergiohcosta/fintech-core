import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface RegisterRequest {
  name: string;
  document?: string;
  adminName: string;
  adminEmail: string;
  password: string;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);
  private readonly API_URL = '/auth'; // O Proxy cuida do resto

  register(data: RegisterRequest): Observable<any> {
    return this.http.post(`${this.API_URL}/register`, data);
  }

  login(credentials: { email: string; password: string }): Observable<{ token: string }> {
    return this.http.post<{ token: string }>(`${this.API_URL}/login`, credentials);
  }

  // Método auxiliar para salvar o token
  saveToken(token: string) {
    localStorage.setItem('auth_token', token);
  }

  // Método para recuperar o token
  getToken() {
    return localStorage.getItem('auth_token');
  }
}