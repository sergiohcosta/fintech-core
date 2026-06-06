import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router'; // Precisamos disso para mandar o usuário pro Login ao sair
import { Observable, tap } from 'rxjs';
import { jwtDecode } from 'jwt-decode'; // A ferramenta que instalamos

// 1. O Contrato: Definimos o que esperamos encontrar DENTRO do token
export interface TokenPayload {
  sub: string;       // Email
  name: string;      // Nome do usuário
  tenant_id: string; // ID da empresa
  role: 'ADMIN' | 'USER';
  exp: number;       // Expiração (Unix timestamp)
}

// O Contrato do Registro (que você já tinha)
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
  private router = inject(Router); // Injetamos o roteador
  
  private readonly API_URL = '/auth';
  private readonly TOKEN_KEY = 'auth_token';

  // 2. O "Alto-Falante" (Signal):
  // É uma variável especial do Angular. Quando ela muda, 
  // qualquer tela que estiver usando ela atualiza sozinha.
  // Começa como 'null' (ninguém logado).
  currentUser = signal<TokenPayload | null>(null);
  isAdmin = computed(() => this.currentUser()?.role === 'ADMIN');

  constructor() {
    // Quando o serviço nasce (no F5), tentamos ver se já tem token salvo
    this.decodeToken();
  }

  register(data: RegisterRequest): Observable<any> {
    return this.http.post(`${this.API_URL}/register`, data);
  }

  login(credentials: { email: string; password: string }): Observable<{ token: string }> {
    return this.http.post<{ token: string }>(`${this.API_URL}/login`, credentials)
      .pipe(
        // O 'tap' é um "espião". Ele olha a resposta SEM atrapalhar o fluxo.
        tap(response => {
          this.saveToken(response.token); // Guarda na gaveta
          this.decodeToken(); // Lê o crachá e avisa o app
        })
      );
  }

  logout() {
    localStorage.removeItem(this.TOKEN_KEY); // Rasga o crachá
    this.currentUser.set(null); // Avisa: "Ninguém logado"
    this.router.navigate(['/login']); // Manda pra porta de entrada
  }

  saveToken(token: string) {
    localStorage.setItem(this.TOKEN_KEY, token);
  }

  setToken(token: string): void {
    this.saveToken(token);
    this.decodeToken();
  }

  getToken() {
    return localStorage.getItem(this.TOKEN_KEY);
  }

  /**
   * 🎓 CONCEITO: Segurança no Frontend
   * Verifica se o token existe e se ainda é válido (não expirou).
   */
  isAuthenticated(): boolean {
    const token = this.getToken();
    if (!token) return false;

    try {
      const decoded = jwtDecode<TokenPayload>(token);
      const currentTime = Math.floor(Date.now() / 1000);
      return decoded.exp > currentTime;
    } catch {
      return false;
    }
  }

  // 3. O Tradutor: A mágica acontece aqui
  private decodeToken() {
    const token = this.getToken();
    if (token) {
      try {
        // Usa a biblioteca para ler o token
        const decoded = jwtDecode<TokenPayload>(token);
        
        // Atualiza o Signal (o alto-falante) com os dados lidos
        this.currentUser.set(decoded);
        
      } catch (error) {
        // Se o token estiver estragado, faz logout por segurança
        this.logout();
      }
    }
  }
}