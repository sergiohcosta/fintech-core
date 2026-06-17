# Design: Migração de JWT para Cookie httpOnly

**Issue:** [#91](https://github.com/sergiohcosta/fintech-core/issues/91)  
**Data:** 2026-06-17  
**Status:** Aprovado  

---

## Contexto

O JWT é atualmente armazenado em `localStorage`. Qualquer script JavaScript com acesso à página (XSS) pode ler e exfiltrar o token. A solução é mover o token para um cookie `httpOnly`, que o browser gerencia e o JavaScript nunca acessa.

A abordagem escolhida é **cookie httpOnly + endpoint `/auth/me`**: o backend seta o cookie no login e o frontend chama `/auth/me` para obter o perfil do usuário, tanto no boot quanto logo após o login.

---

## Arquitetura — Fluxo Antes e Depois

**Antes:**
```
login → { token } no body → localStorage → interceptor → Authorization: Bearer <token>
boot  → jwtDecode(localStorage) → currentUser signal
```

**Depois:**
```
login → Set-Cookie: auth_token (httpOnly; Secure; SameSite=Strict) → body: UserProfile
boot  → GET /auth/me (cookie enviado automaticamente) → currentUser signal
logout → POST /auth/logout → backend expira o cookie → redireciona para /login
```

O `SecurityFilter` lê o token do cookie em todas as requisições autenticadas. O header `Authorization: Bearer` é mantido como fallback para Swagger UI e ferramentas de dev.

---

## Backend

### Cookie

Criado via `ResponseCookie` do Spring (suporte nativo a `SameSite`):

```java
ResponseCookie.from("auth_token", token)
    .httpOnly(true)
    .secure(true)
    .path("/")
    .maxAge(Duration.ofHours(2))
    .sameSite("Strict")
    .build();
```

- `HttpOnly` — inacessível a JavaScript
- `Secure` — enviado apenas via HTTPS (browsers permitem em `localhost` por exceção de spec)
- `SameSite=Strict` — não enviado em navegações cross-site
- `MaxAge=2h` — alinhado com a expiração atual do JWT

### `SecurityFilter` — `recoverToken`

Lê cookie primeiro, Bearer como fallback:

```java
private String recoverToken(HttpServletRequest request) {
    if (request.getCookies() != null)
        for (Cookie c : request.getCookies())
            if ("auth_token".equals(c.getName())) return c.getValue();
    var header = request.getHeader("Authorization");
    return header != null ? header.replace("Bearer ", "") : null;
}
```

### `AuthController` — mudanças

| Endpoint | Antes | Depois |
|----------|-------|--------|
| `POST /auth/login` | retorna `{ token }` no body | seta cookie + retorna `UserProfileResponseDTO` |
| `POST /auth/accept-invite` | retorna `{ token }` no body | seta cookie + retorna `UserProfileResponseDTO` |
| `GET /auth/me` | não existe | retorna `UserProfileResponseDTO` via `@AuthenticationPrincipal` |
| `POST /auth/logout` | não existe | expira o cookie (`MaxAge=0`) + retorna 204 |

### `UserProfileResponseDTO` (novo)

```java
public record UserProfileResponseDTO(
    String email,
    String name,
    String role,
    String tenantId
) {}
```

`LoginResponseDTO` é **removido**.

### CORS

`allowCredentials(true)` já está configurado em `SecurityConfigurations`. Nenhuma mudança necessária.

---

## Frontend

### `AuthService`

Remove toda a lógica de `localStorage` e `jwtDecode`. Interface `TokenPayload` renomeada para `UserProfile`.

```typescript
export interface UserProfile {
  email: string;
  name: string;
  role: 'ADMIN' | 'MEMBER';
  tenantId: string;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);
  private router = inject(Router);

  currentUser = signal<UserProfile | null>(null);
  isAdmin = computed(() => this.currentUser()?.role === 'ADMIN');

  constructor() {
    this.loadCurrentUser().subscribe();
  }

  login(credentials: { email: string; password: string }): Observable<UserProfile> {
    return this.http.post<UserProfile>('/auth/login', credentials, { withCredentials: true })
      .pipe(tap(profile => this.currentUser.set(profile)));
  }

  logout(): void {
    this.http.post('/auth/logout', {}, { withCredentials: true }).subscribe();
    this.currentUser.set(null);
    this.router.navigate(['/login']);
  }

  loadCurrentUser(): Observable<UserProfile | null> {
    return this.http.get<UserProfile>('/auth/me', { withCredentials: true }).pipe(
      tap(profile => this.currentUser.set(profile)),
      catchError(() => { this.currentUser.set(null); return of(null); })
    );
  }

  isAuthenticated(): boolean {
    return this.currentUser() !== null;
  }
}
```

**Removidos:** `TOKEN_KEY`, `getToken()`, `saveToken()`, `setToken()`, `decodeToken()`.

### `authInterceptor`

Remove o header `Authorization`. Adiciona `withCredentials: true` em todas as requests:

```typescript
export const authInterceptor: HttpInterceptorFn = (req, next) =>
  next(req.clone({ withCredentials: true }));
```

### `AuthGuard`

Precisa se tornar assíncrono: no boot, `currentUser` ainda é `null` enquanto `/auth/me` não resolveu. O guard deve aguardar a chamada antes de decidir:

```typescript
export const authGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);

  if (authService.currentUser() !== null) return true;

  return authService.loadCurrentUser().pipe(
    map(user => user !== null ? true : router.createUrlTree(['/login']))
  );
};
```

Se o signal já está populado (navegação interna após boot), retorna `true` síncronamente. Se ainda null (acesso direto via URL), aguarda `/auth/me`.

### `AcceptInviteComponent`

Linha 81 chama `authService.setToken(response.token)` — método que será removido. Após a migração, `POST /auth/accept-invite` retorna `UserProfileResponseDTO` + seta o cookie. O componente passa a fazer:

```typescript
next: (profile) => {
  this.authService.currentUser.set(profile);
  this.router.navigate(['/dashboard']);
}
```

### Dependência `jwtDecode`

Remover de `package.json` após confirmar que não há outros usos no projeto.

---

## Contrato OpenAPI (`api-spec/openapi.yaml`)

### Schemas

**Remover:** `LoginResponse`

**Adicionar:**
```yaml
UserProfileResponse:
  type: object
  properties:
    email:
      type: string
    name:
      type: string
    role:
      type: string
      enum: [ADMIN, MEMBER]
    tenantId:
      type: string
```

### Endpoints alterados

| Método | Path | Mudança |
|--------|------|---------|
| `POST` | `/auth/login` | response schema: `LoginResponse` → `UserProfileResponse` |
| `POST` | `/auth/accept-invite` | response schema: `LoginResponse` → `UserProfileResponse` |
| `GET` | `/auth/me` | novo — retorna `UserProfileResponse`; requer cookie |
| `POST` | `/auth/logout` | novo — 204 No Content; expira cookie |

---

## Testes

### Backend (Controller + MockMvc)

- `POST /auth/login` com credenciais válidas → cookie `auth_token` presente no response header; body contém perfil sem campo `token`
- `POST /auth/login` com credenciais inválidas → 401, sem cookie
- `GET /auth/me` com cookie válido → 200 com perfil
- `GET /auth/me` sem cookie → 401
- `POST /auth/logout` → response tem `Set-Cookie: auth_token=; MaxAge=0`
- Testes de role existentes (403) → passam sem modificação (regressão)

### Frontend (Vitest)

- `AuthService.login` → `currentUser` hidratado com perfil; `localStorage` não tocado
- `AuthService.loadCurrentUser` quando API retorna 401 → `currentUser` null, sem exceção propagada
- `authInterceptor` → toda request tem `withCredentials: true`; sem header `Authorization`
- `AuthGuard` com `currentUser = null` → redireciona para `/login`

---

## O que não muda

- Duração do token: 2h (mantida — sessão curta é mais segura)
- `TokenService.generateToken` e `validateToken` — sem alteração
- `SecurityConfigurations` — regras de role e endpoints públicos intocados
- `LoginRateLimiter` — intocado
- Fluxo de convite — mesma lógica, só o transporte do token muda

---

## Riscos e mitigações

| Risco | Mitigação |
|-------|-----------|
| `Secure` flag bloqueia cookie em HTTP local | Browsers fazem exceção para `localhost`; se necessário, configurar via `@Value` por perfil |
| SPA em subdomínio diferente do backend | `SameSite=Strict` não bloqueia (mesmo site); CORS com `allowCredentials` já configurado |
| Swagger UI perde autenticação | Bearer mantido como fallback em `SecurityFilter` |
| Race condition no boot (guard antes de `/auth/me` resolver) | `loadCurrentUser` é chamado no construtor; guard verifica o signal que só é usado depois do boot |
